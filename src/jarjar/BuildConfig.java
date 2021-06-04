package jarjar;

import ox.File;
import ox.IO;
import ox.Json;
import ox.x.XList;

public class BuildConfig {

  private final File projectDir;
  private final Json json;

  private BuildConfig(File projectDir) {
    this.projectDir = projectDir;

    File jsonFile = projectDir.child("build.json");
    json = jsonFile.exists() ? IO.from(jsonFile).toJson() : Json.object();
  }

  public boolean shouldInclude(String fileName) {
    if (json.hasKey("exclude")) {
      for (String s : json.getJson("exclude").asStringArray()) {
        if (fileName.startsWith(s)) {
          return false;
        }
      }
    }
    return true;
  }

  public boolean shouldInclude(File jarFile, String fileName) {
    Json config = json.getJson(jarFile.getName());

    if (config == null) {
      return true;
    }

    if (!config.hasKey("include")) {
      return true;
    }

    XList<String> whitelist = config.getJson("include").asStringArray();
    for (String s : whitelist) {
      if (fileName.startsWith(s)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return projectDir.getName();
  }

  public static BuildConfig getForProject(File file) {
    return new BuildConfig(file);
  }

}
