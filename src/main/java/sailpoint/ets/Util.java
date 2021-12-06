package sailpoint.ets;

import java.io.IOException;
import java.io.InputStream;

import com.amazonaws.services.lambda.runtime.Context;

import com.google.gson.Gson;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Util {

  final static Logger log = LogManager.getLogger(Util.class);

  public static void logEnvironment(Object event, Context context, Gson gson)
      throws IOException, XmlPullParserException {
    // log execution details
    log.debug("ENVIRONMENT VARIABLES: " + gson.toJson(System.getenv()));
    log.debug("CONTEXT: " + gson.toJson(context));
    // log event details
    log.debug("EVENT: " + gson.toJson(event));
    log.debug("EVENT TYPE: " + event.getClass().toString());

    InputStream is = Util.class.getClassLoader()
        .getResourceAsStream("META-INF/maven/ETS-examples/lambda-java-dynamicapproval/pom.xml");
    MavenXpp3Reader reader = new MavenXpp3Reader();
    Model model = reader.read(is);
    log.debug("MODEL: " + model.getId());
    log.debug("GROUP ID: " + model.getGroupId());
    log.debug("ARTEFACT ID: " + model.getArtifactId());
    log.debug("VERSION: " + model.getVersion());
  }
}