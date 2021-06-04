package jarjar;

import ox.File;
import ox.HttpRequest;
import ox.Json;
import ox.x.XList;

public class JarJarCommandLine {

  private void run(Json json) {
    json.log();
    File.temp(temp -> {
      JarJar.project(File.ofPath(json.get("project"))).main(json.get("main")).build(temp);
      uploadToSignedUrl(json.getJson("signedUrl"), "application/java-archive", temp);
    });
  }

  private void uploadToSignedUrl(Json signedUrl, String contentType, File file) {
    HttpRequest request = HttpRequest.post(signedUrl.get("uploadUrl")).chunk(4_000_000);
    XList<Json> formFields = signedUrl.getJson("formFields").asJsonArray();
    formFields.forEach(f -> {
      f.forEach((k, v) -> {
        request.part(k, (String) v);
      });
    });
    request.part("Content-Type", contentType)
        .part("file", file.inputStream())
        .checkStatus();
  }

  public static void main(String[] args) {
    new JarJarCommandLine().run(new Json(args[0]));
  }


}
