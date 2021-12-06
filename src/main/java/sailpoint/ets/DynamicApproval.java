package sailpoint.ets;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.amazonaws.services.secretsmanager.model.DecryptionFailureException;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import com.amazonaws.services.secretsmanager.model.InternalServiceErrorException;
import com.amazonaws.services.secretsmanager.model.InvalidParameterException;
import com.amazonaws.services.secretsmanager.model.InvalidRequestException;
import com.amazonaws.services.secretsmanager.model.ResourceNotFoundException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import retrofit2.Response;
import sailpoint.identitynow.api.IdentityNowService;
import sailpoint.identitynow.api.object.Identity;
import sailpoint.identitynow.api.object.QueryObject;
import sailpoint.identitynow.api.object.SearchQuery;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DynamicApproval implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

  final static Logger log = LogManager.getLogger(DynamicApproval.class);
  IdentityNowService idnService;
  Map<String,Object> attrs = new HashMap<String,Object>();

  Gson gson     = new GsonBuilder().setPrettyPrinting().create();
  String tenant = null;
  Boolean useDepartment = false;

  public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context)
  {
    log.trace("Entering handleRequest");

    try {
      Util.logEnvironment(event, context, gson);
    } catch (IOException e2) {
      log.error(e2.getMessage());
      e2.printStackTrace();
    } catch (XmlPullParserException e2) {
      log.error(e2.getMessage());
      e2.printStackTrace();
    }

    
    APIGatewayV2WebSocketResponse response = new APIGatewayV2WebSocketResponse();
    AmazonS3 client     = null;
    
    Map<String,String> stageVars = event.getStageVariables();
    log.debug("stage variables: " + stageVars);
//		String clientId     = stageVars.get("clientId");
//		String clientSecret = stageVars.get("clientSecret");
    String bucket       = stageVars.get("bucket");
    
    try {

      String body = "{}";
      try {
        String requestString = event.getBody();
        log.debug("event: " + event.toString());
        log.debug("body:  " + requestString);
        
        JSONParser parser     = new JSONParser();
        JSONObject jo         = (JSONObject) parser.parse(requestString);
        log.debug("requestJsonObject: " + jo);

        if (null != jo) {
          // JSONObject invocation = (JSONObject) jo.get("startInvocationInput");
          // JSONObject input      = (JSONObject) invocation.get("input");
          JSONObject detail     = (JSONObject) jo.get("detail");
          
          if (null != detail) {
            response.setIsBase64Encoded(false);
            response.setStatusCode(200);
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Type", "application/json");
            response.setHeaders(headers);
            // log execution details
        
            JSONObject metadata      = (JSONObject) detail.get("_metadata");
            JSONObject requestedFor   = (JSONObject) detail.get("requestedFor");
            JSONObject requestedBy    = (JSONObject) detail.get("requestedBy");
            JSONArray  requestedItems = (JSONArray)  detail.get("requestedItems");
      
            // log.debug("metadata: " + metadata + "\n\n");
            log.debug("requestedFor:   " + requestedFor + "\n\n");
            log.debug("requestedBy:    " + requestedBy + "\n\n");
            log.debug("requestedItems: " + requestedItems + "\n\n");
      
            String secret       = metadata.get("secret").toString();
            String url          = metadata.get("callbackURL").toString();
            String requestee    = requestedFor.get("name").toString();
            String requesteeId  = requestedFor.get("id").toString();
            String requester    = requestedBy.get("name").toString();
            String item         = ((JSONObject)requestedItems.get(0)).get("name").toString();
            
            log.debug("requestee: " + requestee + "\n");
            log.debug("requester: " + requester + "\n");
            log.debug("item     : " + item + "\n");
            
            // Create IDN session
            createSession(url);

            // Fetch identity attributes
            try {
              attrs  = getAttributes(idnService, requesteeId);
            } catch (Exception e) {
              log.error("Error checking identity attributes: " + e.getLocalizedMessage());
            }

            String department = (String) attrs.get("department");
            if (null != department && !"".equals(department))
              useDepartment = true;

            String approver = "";
            String approverId = "";
            String file   = "dynamicApproval.csv";

            if (useDepartment) {
              file = "dynamicDepartmentApproval.csv";
              item = department;
            }

            HashMap<String,List<String>> matrix = getDynamicApprovalCSV(client,bucket,file);

            if (matrix.get(item) != null) {
              approver   = matrix.get(item).get(0);
              approverId = matrix.get(item).get(1);
              // WHEN sent directly to the API GATEWAY
              // body = "{\n"
              //     + "  \"name\": \"" + matrix.get(item).get(0) + "\",\n"
              //     + "  \"id\": \""   + matrix.get(item).get(1) + "\",\n"
              //     + "  \"type\": \"IDENTITY\"\n"
              //     + "}\n";
            }  else {
              log.debug("Didn't find an entry for " + item + " in the matrix: " + matrix);
            }
                    
            // When sent through the EVENT BUS:
            body = "{\n"
                + "    \"secret\": \"" + secret + "\",\n"
                + "    \"output\": {\n"
                + "  \"name\": \"" + approver + "\",\n"
                + "  \"id\": \"" + approverId + "\",\n"
                + "      \"type\": \"IDENTITY\"\n"
                + "    }\n"
                + "}\"\n";

            response.setBody(body);
            log.debug(response + "\n");
            sendCallBack(body,url);

            } else {
              log.debug("ERROR: Couldn't get details from jsonObject");
            }
        } else {
          log.debug("ERROR: Couldn't parse jsonObject");
        }
        
  //	    if (null != callbackURL) {
  //	    	sendCallBack(logger,body,callbackURL);
  //	    }
        
        log.debug("Done.\n\n");
      } catch (ParseException e) {
        e.printStackTrace();
      }
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }

    log.trace("Leaving handleRequest");
    return response;
  }
  
  /**
   * Creates the IDN session based on the URL
   * Needs to retrieve the PAT from the Secrets manager
   * @param url
   * @throws MalformedURLException
   * @throws ParseException
   */
  private void createSession(String url) throws MalformedURLException, ParseException {

    log.trace("Entering createSession");

    // https://enterprise802.api.identitynow.com/beta/trigger-invocations/57d4ba02-e2a1-423b-943f-d8d1c6ae860a/complete
    URL uurl = new URL(url);
    tenant    = uurl.getHost();
    String tenantUrl = "https://" + tenant;
    log.debug("tenant: " + tenantUrl);
    String[] splitHost   = tenant.split("\\.");
    String    tenantName = splitHost[0];

    // Fetch credentials for IDN
    JSONObject idnSecrets = getPAT(tenantName);
    String clientId     = idnSecrets.get("clientId").toString();
    String clientSecret = idnSecrets.get("clientSecret").toString();
    // log.debug("clientId:     " + clientId);
    // log.debug("clientSecret: " + clientSecret);
    
    log.debug("Getting idnService");
    idnService = new IdentityNowService( tenantUrl, clientId, clientSecret, null, 60000L );
    log.debug("Got idnService");
    
    log.debug( "Checking credentials..." );
    try {
      idnService.createSession();
      log.info("Session created.");
    } catch ( Exception e ) {
      log.error( "Error Logging into IdentityNow.  Check your credentials and try again. [" + e.getLocalizedMessage() +  "]" );
      e.printStackTrace();
      System.exit(0);
    }

    log.trace("Leaving createSession");
  }

/**
   * Gets the authentication info for the IDN tenant
   * @param String tenant
   * @return JSONObject : Map with the clientId and clientSecret
   * @throws ParseException
   */
  private JSONObject getPAT(String tenant) throws ParseException {
    log.trace("Entering getPAT: " + tenant);

    JSONObject secrets = null;
    String secretName = tenant + "-token";
    String region     = "us-west-1";

    // Create a Secrets Manager client
    AWSSecretsManager client  = AWSSecretsManagerClientBuilder.standard()
                                    .withRegion(region)
                                    .build();
    
    // In this sample we only handle the specific exceptions for the 'GetSecretValue' API.
    // See https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_GetSecretValue.html
    // We rethrow the exception by default.
    
    String secret              = null;
    String decodedBinarySecret = null;
    GetSecretValueRequest getSecretValueRequest = new GetSecretValueRequest()
                    .withSecretId(secretName);
    GetSecretValueResult getSecretValueResult = null;

    try {
        getSecretValueResult = client.getSecretValue(getSecretValueRequest);
    } catch (DecryptionFailureException e) {
        // Secrets Manager can't decrypt the protected secret text using the provided KMS key.
        // Deal with the exception here, and/or rethrow at your discretion.
        throw e;
    } catch (InternalServiceErrorException e) {
        // An error occurred on the server side.
        // Deal with the exception here, and/or rethrow at your discretion.
        throw e;
    } catch (InvalidParameterException e) {
        // You provided an invalid value for a parameter.
        // Deal with the exception here, and/or rethrow at your discretion.
        throw e;
    } catch (InvalidRequestException e) {
        // You provided a parameter value that is not valid for the current state of the resource.
        // Deal with the exception here, and/or rethrow at your discretion.
        throw e;
    } catch (ResourceNotFoundException e) {
        // We can't find the resource that you asked for.
        // Deal with the exception here, and/or rethrow at your discretion.
        throw e;
    }

    // Decrypts secret using the associated KMS CMK.
    // Depending on whether the secret is a string or binary, one of these fields will be populated.
    if (getSecretValueResult.getSecretString() != null) {
      secret = getSecretValueResult.getSecretString();
    } else {
      decodedBinarySecret = new String(Base64.getDecoder().decode(getSecretValueResult.getSecretBinary()).array());
    }

    log.debug("secret: " + secret);
    log.debug("decoded: " + decodedBinarySecret);

    JSONParser parser = new JSONParser();
    secrets = (JSONObject) parser.parse(secret);
    log.trace("Leaving getPAT");
    return secrets;
  }

  /**
   * Check for toxic attribute on the identity in IDN
   * Currently hardcoded to search for `ccom` attribute
   * This relies on the IdentityNowService from Neil McGlennon
   * @param idnService2
   * @param requesteeId
   * @return boolean : allowed or not
   * @throws IOException
   * @throws Exception
   */
  private Map<String,Object> getAttributes(IdentityNowService idnService2, String requesteeId) throws IOException, Exception {
    log.trace("Entering getAttributes: " + requesteeId);
    
    Map<String,Object> attributes = new HashMap<String,Object>();
    String searchQueryString = "id:" + requesteeId;
    log.debug("search query string: " + searchQueryString);
    SearchQuery searchQuery = new SearchQuery(searchQueryString);
    log.debug("using searchQuery: " + searchQuery);
    QueryObject query = new QueryObject(searchQuery);
    
    log.debug("Executing search");
    Response<List<Identity>> response = idnService.getSearchService().getIdentities(false, 0, 10, query).execute();
    log.debug("Got response: " + response);

    if (response.isSuccessful()) {
      log.debug("body: " + response.body());
      log.debug("response: " + response.toString());
      List<Identity> ids = response.body();
      log.debug("Found " + ids.size() + " identities");
    
      int counter = 0;
      for (Identity identity : ids) {
        log.debug(identity.toString());
        counter++;
        log.debug(counter + " : id           : " + identity.getId());
        log.debug(counter + " : name         : " + identity.getSource().getName());
        log.debug(counter + " : displayName  : " + identity.getDisplayName());
        log.debug(counter + " : attributes   : " + identity.getAttributes());
        log.debug("===============");
        attributes = identity.getAttributes();
        
      }
    } else {
      log.debug("Response was not successful for identity search.");
    }
    log.trace("Leaving checkAttributes: " + attributes);
    return attributes;
  }

  private HashMap<String, List<String>> getDynamicApprovalCSV(AmazonS3 client, String bucket, String file) throws IOException {
    log.trace("Entering getDynamicApprovalCSV");
  
    if (null == client) {
      client = getS3Client();
    }
    HashMap<String,List<String>> map = new HashMap<String, List<String>>();

    String[] data = null;
    
    S3Object       s3Object = client.getObject(bucket, file);
    log.debug("Got s3 file.");
    InputStream    is       = null;
    BufferedReader br       = null;
    try {
      is = s3Object.getObjectContent ();
      br = new BufferedReader (new InputStreamReader (is, "UTF-8"));
      String line;
      // Read the CSV one line at a time and process it.
      while ((line = br.readLine ()) != null) {
         data = line.split (",", 0);
         map.put(data[0], Arrays.asList(data[1],data[2]));
      }
    } catch (UnsupportedEncodingException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } finally {
      if (is != null) {
        is.close ();
      }
      if (br != null) {
        br.close ();
      }
    }
    
    log.trace("Leaving getDynamicApprovalCSV: " + map);
    return map;
  }

  public void sendCallBack(String body, String urlString) throws org.apache.http.ParseException, IOException {
    log.trace("Entering sendCallBack");
    
    log.debug("- body: " + body);
    log.debug("- url: " + urlString);

    StringEntity entity = new StringEntity(body, ContentType.APPLICATION_JSON);
    String entityString = EntityUtils.toString(entity, StandardCharsets.UTF_8);
    log.debug("entity: " + entityString);
    
    HttpClient httpClient = HttpClientBuilder.create().build();
    HttpPost   request    = new HttpPost(urlString);
    request.setEntity(entity);
    request.setHeader("Accept", "application/json");
    request.setHeader("Content-type", "application/json");

    HttpResponse response = null;
    try {
      response = httpClient.execute(request);
    } catch (ClientProtocolException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    log.debug("Status code: " + response.getStatusLine().getStatusCode());
    
    HttpEntity responseEntity = response.getEntity();
    log.debug("responseEntity: " + responseEntity);
    String result = null;
    if (null != responseEntity) {
      try {
        result = EntityUtils.toString(responseEntity, StandardCharsets.UTF_8);
      } catch (org.apache.http.ParseException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    log.debug("Result: " + result);
    log.trace("Leaving sendCallBack");
  }

  
  private AmazonS3 getS3Client() {
    log.trace("Entering getS3client");
    
    // AWSCredentials credentials = new BasicAWSCredentials ("access key", "secret key");
    // Client generation
    AmazonS3 client = AmazonS3ClientBuilder.standard ()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion (System.getenv("AWS_REGION"))
      .build ();
    log.debug("Got s3 client.");
        
    log.trace("Leaving getS3client");
    return client;
  }
}



/*
EVENT body: {
    "version": "0",
    "id": "278d2f65-0fd4-56e7-4f30-4a8f945ef0bb",
    "detail-type": "idn:access-request-dynamic-approver",
    "source": "aws.partner/sailpoint.com/ceaf7121-d3d0-4313-9324-00e2c7713110/idn/access-request-dynamic-approver",
    "account": "904994459557",
    "time": "2021-12-03T17:50:46Z",
    "region": "us-west-1",
    "resources": [],
    "detail": {
        "accessRequestId": "4b4d982dddff4267ab12f0f1e72b5a6d",
        "requestedFor": {
            "id": "2c91808b6ef1d43e016efba0ce470909",
            "name": "Ed Engineer",
            "type": "IDENTITY"
        },
        "requestedItems": [
            {
                "id": "2c91808b6ef1d43e016efba0ce470904",
                "name": "Engineering Access",
                "description": "Engineering Access",
                "type": "ACCESS_PROFILE",
                "operation": "Add",
                "comment": "Ed needs this access for his day to day job activities"
            }
        ],
        "requestedBy": {
            "type": "IDENTITY",
            "id": "2c91808b6ef1d43e016efba0ce470906",
            "name": "Adam Admin"
        },
        "_metadata": {
            "callbackURL": "https://company164-poc.api.identitynow.com/beta/trigger-invocations/37d6df15-0da2-4e8f-b73a-e644ca0385ef/complete",
            "triggerType": "requestResponse",
            "secret": "3a24d1e9-6d2d-484a-aece-7b760d7b9965",
            "triggerId": "idn:access-request-dynamic-approver",
            "invocationId": "37d6df15-0da2-4e8f-b73a-e644ca0385ef"
        }
    }
}
*/
// Response:

//{
//  "approved": true,
//  "comment": "",
//  "approver": "slpt.services",
//	"secret": "askjdhsjdhaskjhdkj"
//}


// 20211202 API GATEWAY EVENT: 
/*
requestJsonObject: {
"startInvocationInput": {
    "input": {
        "requestedFor": {
            "name": "Ed Engineer",
            "id": "2c91808b6ef1d43e016efba0ce470909",
            "type": "IDENTITY"
        },
        "requestedBy": {
            "name": "Adam Admin",
            "id": "2c91808b6ef1d43e016efba0ce470906",
            "type": "IDENTITY"
        },
        "accessRequestId": "4b4d982dddff4267ab12f0f1e72b5a6d",
        "requestedItems": [
            {
                "name": "Engineering Access",
                "description": "Engineering Access",
                "comment": "Ed needs this access for his day to day job activities",
                "id": "2c91808b6ef1d43e016efba0ce470904",
                "type": "ACCESS_PROFILE",
                "operation": "Add"
            }
        ]
    },
    "triggerId": "idn:access-request-dynamic-approver",
    "contentJson": {}
},
"created": "2021-12-03T16:02:53.091032Z",
"triggerId": "idn:access-request-dynamic-approver",
"subscriptionName": "MKS Dynamic Approver Trigger",
"id": "568293de-8860-4672-b5a9-0f7dbe1c2d03",
"completed": null,
"completeInvocationInput": {
    "output": null,
    "localizedError": null
},
"type": "TEST",
"subscriptionId": "ceaf7121-d3d0-4313-9324-00e2c7713110"
}
*/