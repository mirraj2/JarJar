package jarjar;

import ox.File;
import ox.Json;
import ox.x.XList;

public class JarJarCommandLine {

  private void run(Json json) {
    json.log();
    XList<String> projects;
    if (json.hasKey("projects")) {
      projects = json.getJson("projects").asStringArray();
    } else {
      projects = XList.of(json.get("project"));
    }
    JarJar jarjar = JarJar.project(File.ofPath(projects.get(0)));
    for (String project : projects.offset(1)) {
      jarjar.addProject(File.ofPath(project));
    }
    if (json.hasKey("blacklist")) {
      json.getJson("blacklist").asStringArray().forEach(jarjar::blacklist);
    }
    jarjar.main(json.get("main"));
    if (json.getBoolean("skipCompile", false)) {
      jarjar = jarjar.skipCompile();
    }
    jarjar.build(File.ofPath(json.get("target")));
  }

  public static void main(String[] args) {
    // new JarJarCommandLine()
    // .run(Json.object().with("project", "/users/jason/workspace/bowser").with("target",
    // "/users/jason/Downloads/bowser.jar").with("skipCompile", true));
    new JarJarCommandLine().run(new Json(args[0]));
  }

}
