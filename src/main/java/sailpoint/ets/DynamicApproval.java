package sailpoint.ets;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

public class DynamicApproval implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {
  LambdaLogger       logger;

  Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
  public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent event, Context context)
  {
    logger = context.getLogger();
    try {
      Util.logEnvironment(event, context, gson);
    } catch (IOException e2) {
      logger.log(e2.getMessage());
      e2.printStackTrace();
    } catch (XmlPullParserException e2) {
      logger.log(e2.getMessage());
      e2.printStackTrace();
    }

    
    APIGatewayV2WebSocketResponse response = new APIGatewayV2WebSocketResponse();
    AmazonS3 client     = null;
    String tenant       = null;
    
    Map<String,String> stageVars = event.getStageVariables();
    logger.log("stage variables: " + stageVars);
//		String clientId     = stageVars.get("clientId");
//		String clientSecret = stageVars.get("clientSecret");
    String bucket       = stageVars.get("bucket");
    
    try {
      HashMap<String,List<String>> matrix = getDynamicApprovalCSV(client,bucket);

      String body = "{}";
      try {
        String requestString = event.getBody();
        logger.log("event: " + event.toString());
        logger.log("body:  " + requestString);
        
        JSONParser parser     = new JSONParser();
        JSONObject jo         = (JSONObject) parser.parse(requestString);
        logger.log("requestJsonObject: " + jo);

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
      
            // logger.log("metadata: " + metadata + "\n\n");
            logger.log("requestedFor:   " + requestedFor + "\n\n");
            logger.log("requestedBy:    " + requestedBy + "\n\n");
            logger.log("requestedItems: " + requestedItems + "\n\n");
      
            String secret       = metadata.get("secret").toString();
            String url          = metadata.get("callbackURL").toString();
            String requestee    = requestedFor.get("name").toString();
            String requester    = requestedBy.get("name").toString();
            String item         = ((JSONObject)requestedItems.get(0)).get("name").toString();
            
            logger.log("requestee: " + requestee + "\n");
            logger.log("requester: " + requester + "\n");
            logger.log("item     : " + item + "\n");
            
            if (matrix.get(item) != null) {
              // WHEN sent directly to the API GATEWAY
              // body = "{\n"
              //     + "  \"name\": \"" + matrix.get(item).get(0) + "\",\n"
              //     + "  \"id\": \""   + matrix.get(item).get(1) + "\",\n"
              //     + "  \"type\": \"IDENTITY\"\n"
              //     + "}\n";
              
                    
              // When sent through the EVENT BUS:
              body = "{\n"
                  + "    \"secret\": \"" + secret + "\",\n"
                  + "    \"output\": {\n"
                  + "  \"name\": \"" + matrix.get(item).get(0) + "\",\n"
                  + "  \"id\": \"" + matrix.get(item).get(1) + "\",\n"
                  + "      \"type\": \"IDENTITY\"\n"
                  + "    }\n"
                  + "}\"\n";
              }  else {
                  logger.log("Didn't find an entry in the matrix: " + matrix);
              }
            response.setBody(body);
            logger.log(response + "\n");
            sendCallBack(body,url);

            } else {
              logger.log("ERROR: Couldn't get details from jsonObject");
            }
        } else {
          logger.log("ERROR: Couldn't parse jsonObject");
        }
        
  //	    if (null != callbackURL) {
  //	    	sendCallBack(logger,body,callbackURL);
  //	    }
        
        logger.log("Done.\n\n");
      } catch (ParseException e) {
        e.printStackTrace();
      }
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
    return response;
  }
  
  private HashMap<String, List<String>> getDynamicApprovalCSV(AmazonS3 client, String bucket) throws IOException {
    logger.log("Entering getDynamicApprovalCSV");
    
    String file   = "dynamicApproval.csv";

    if (null == client) {
      client = getS3Client();
    }
    HashMap<String,List<String>> map = new HashMap<String, List<String>>();

    String[] data = null;
    
    S3Object       s3Object = client.getObject(bucket, file);
    logger.log("Got s3 file.");
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
    
    logger.log("Leaving getDynamicApprovalCSV: " + map);
    return map;
  }

//	public void sendCallBack(String body, String urlString) {
//		logger.log("Entering sendCallBack");
//		
//		URL url = null;
//		try {
//			url = new URL(urlString);
//		} catch (MalformedURLException e1) {
//			// TODO Auto-generated catch block
//			e1.printStackTrace();
//		}
//		URLConnection con;
//		try {
//			con = url.openConnection();
//
//			HttpURLConnection http = (HttpURLConnection)con;
//			http.setRequestMethod("POST"); // PUT is another valid option
//			http.setDoOutput(true);
//			
//			byte[] out = body.getBytes(StandardCharsets.UTF_8);
//			int length = out.length;
//
//			http.setFixedLengthStreamingMode(length);
//			http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
//			http.connect();
//			logger.log("Sending response");
//			try(OutputStream os = http.getOutputStream()) {
//			    os.write(out);
//			    logger.log("Response code: " + http.getResponseCode());
//					// Read response
//					try(BufferedReader br = new BufferedReader(
//						  new InputStreamReader(http.getInputStream(), "utf-8"))) {
//						    StringBuilder response = new StringBuilder();
//						    String responseLine = null;
//						    while ((responseLine = br.readLine()) != null) {
//						        response.append(responseLine.trim());
//						    }
//						    
//						    logger.log("Answer: "+ response.toString());
//					}
//			}
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		logger.log("Leaving sendCallBack");
//	}
  
  public void sendCallBack(String body, String urlString) throws org.apache.http.ParseException, IOException {
    logger.log("Entering sendCallBack");
    
    logger.log("- body: " + body);
    logger.log("- url: " + urlString);

    StringEntity entity = new StringEntity(body, ContentType.APPLICATION_JSON);
    String entityString = EntityUtils.toString(entity, StandardCharsets.UTF_8);
    logger.log("entity: " + entityString);
    
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
    logger.log("Status code: " + response.getStatusLine().getStatusCode());
    
    HttpEntity responseEntity = response.getEntity();
    logger.log("responseEntity: " + responseEntity);
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
    logger.log("Result: " + result);
    logger.log("Leaving sendCallBack");
  }

  
  private AmazonS3 getS3Client() {
    logger.log("Entering getS3client");
    
    // AWSCredentials credentials = new BasicAWSCredentials ("access key", "secret key");
    // Client generation
    AmazonS3 client = AmazonS3ClientBuilder.standard ()
      .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
      .withRegion (System.getenv("AWS_REGION"))
      .build ();
    logger.log("Got s3 client.");
        
    logger.log("Leaving getS3client");
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