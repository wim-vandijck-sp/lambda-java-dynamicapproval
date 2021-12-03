package sailpoint.ets;

import java.io.IOException;
import java.io.InputStream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.google.gson.Gson;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class Util {

public static void logEnvironment(Object event, Context context, Gson gson) throws IOException, XmlPullParserException
{
  LambdaLogger logger = context.getLogger();
  // log execution details
  logger.log("ENVIRONMENT VARIABLES: " + gson.toJson(System.getenv()));
  logger.log("CONTEXT: " + gson.toJson(context));
  // log event details
  logger.log("EVENT: " + gson.toJson(event));
  logger.log("EVENT TYPE: " + event.getClass().toString());

  InputStream is = Util.class.getClassLoader().getResourceAsStream("META-INF/maven/ETS-examples/lambda-java-dynamicapproval/pom.xml");
  MavenXpp3Reader reader = new MavenXpp3Reader();
  Model model = reader.read(is);
  logger.log("MODEL: " + model.getId());
  logger.log("GROUP ID: " + model.getGroupId());
  logger.log("ARTEFACT ID: " + model.getArtifactId());
  logger.log("VERSION: " + model.getVersion());
  }
}