package jarjar;

import static com.google.common.base.Preconditions.checkState;
import static ox.util.Utils.propagate;

import com.google.common.base.Joiner;

import ox.File;
import ox.Log;
import ox.x.XList;
import ox.x.XSet;

public class JavaCompiler {

  private final File targetDir;
  private final XList<File> classpath = XList.create();

  private JavaCompiler(File targetDir) {
    this.targetDir = targetDir;
  }

  public JavaCompiler classpath(XSet<File> classpath) {
    this.classpath.addAll(classpath);
    return this;
  }

  public void compile(XList<File> sourceDirs) {
    // clean
    // if (!targetDir.getPath().contains("ox/bin")) {
    // targetDir.empty();
    // }

    targetDir.mkdirs();

    XList<String> command = XList.of("javac", "-d", targetDir.getPath(), "-source", "11", "-target", "11",
        "-g:source,lines,vars");
    if (classpath.hasData()) {
      command.add("-classpath", Joiner.on(':').join(classpath));
    }
    command.add("-sourcepath", Joiner.on(':').join(sourceDirs));

    XList<File> javaFiles = XList.create();
    sourceDirs.forEach(sourceDir -> {
      javaFiles.addAll(sourceDir.filterTree(file -> file.extension().equals("java")));
    });
    command.addAll(javaFiles.map(f -> f.getPath()));

    Log.debug(Joiner.on(" ").join(command));
    try {
      Process p = new ProcessBuilder().inheritIO().command(command).start();
      int returnValue = p.waitFor();
      checkState(returnValue == 0, returnValue);
    } catch (Exception e) {
      throw propagate(e);
    }
  }

  public static JavaCompiler target(File targetDir) {
    return new JavaCompiler(targetDir);
  }

}

