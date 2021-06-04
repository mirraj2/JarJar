package jarjar;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static ox.util.Utils.formatBytes;
import static ox.util.Utils.only;
import static ox.util.Utils.propagate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.google.common.base.Stopwatch;

import ox.File;
import ox.IO;
import ox.Log;
import ox.util.Utils;
import ox.x.XList;
import ox.x.XMultimap;
import ox.x.XSet;

public class JarJar {

  private final File projectDir;
  private String mainClass = "";
  private File outputFile;
  private XSet<File> seenProjects = XSet.create();
  private boolean verbose = false, compile = true;

  private JarJar(File projectDir) {
    this.projectDir = checkNotNull(projectDir);
  }

  public JarJar main(String mainClass) {
    this.mainClass = mainClass;
    return this;
  }

  public JarJar skipCompile() {
    compile = false;
    return this;
  }

  public void build(File outputFile) {
    this.outputFile = outputFile;

    Stopwatch watch = Stopwatch.createStarted();
    XMultimap<File, BuildConfig> classpath = compileProject(projectDir, 0);
    Log.debug("Compiled " + watch);

    watch.reset().start();
    exportJar(classpath);
    Log.debug("JAR created: %s %s (%s)", outputFile, watch, Utils.formatBytes(outputFile.length()));
  }

  private void exportJar(XMultimap<File, BuildConfig> classpath) {
    Zipper zipper = new Zipper(outputFile);
    XSet<String> exportedFiles = XSet.create();
    try {
      classpath.asMap().forEach((classpathEntry, buildConfigs) -> {
        try {
          if (classpathEntry.isDirectory()) {
            Log.debug("Copying tree: %s (%s)", classpathEntry, formatBytes(classpathEntry.totalSize()));
            classpathEntry.walkTree(file -> {
              if (!file.isDirectory()) {
                String name = file.getRelativePath(classpathEntry);
                if (!only(buildConfigs).shouldInclude(name)) {
                  Log.warn("Skipping " + name);
                  return;
                }
                if (verbose || file.length() > 1_000_000) {
                  Log.debug(name + " " + formatBytes(file.length()));
                }
                zipper.putNextEntry(name, file.inputStream());
              }
            });
          } else {
            copyJarToJar(classpathEntry, zipper, exportedFiles, only(buildConfigs));
          }
        } catch (Exception e) {
          throw propagate(e);
        }
      });
      addManifest(zipper);
    } finally {
      zipper.close();
    }
  }

  private void addManifest(Zipper zipper) {
    StringBuilder sb = new StringBuilder("Manifest-Version: 1.0\n");
    if (!mainClass.isEmpty()) {
      sb.append("Main-Class: " + mainClass + "\n");
    }
    zipper.putNextEntry("META-INF/MANIFEST.MF", IO.from(sb.toString()).asStream());
  }

  private void copyJarToJar(File sourceFile, Zipper zipper, XSet<String> exportedFiles, BuildConfig config) {
    Log.debug("Copying jar: %s (%s)", sourceFile, Utils.formatBytes(sourceFile.length()));
    Unzipper unzipper = new Unzipper(IO.from(sourceFile).asStream());
    try {
      while (unzipper.hasNext()) {
        if (!shouldCopyFile(sourceFile, unzipper, exportedFiles, config)) {
          unzipper.next();
          continue;
        }
        String fileName = unzipper.getName();
        if (verbose || unzipper.getSize() > 1_000_000) {
          Log.debug("%s (%s)", fileName, formatBytes(unzipper.getSize()));
        }
        zipper.putNextEntry(fileName, unzipper);
      }
    } finally {
      unzipper.finish();
    }
  }

  private boolean shouldCopyFile(File jarFile, Unzipper unzipper, XSet<String> exportedFiles, BuildConfig config) {
    if (unzipper.isDirectory()) {
      return false;
    }
    String fileName = unzipper.getName();
    if (fileName.startsWith("META-INF")) {
      return false;
    }
    if (!config.shouldInclude(jarFile, fileName)) {
      Log.debug("Skipping non-whitelisted file: " + fileName);
      return false;
    }
    if (!exportedFiles.add(fileName)) {
      if (!fileName.equals("module-info.class")) {
        Log.warn("Duplicate file: " + fileName);
      }
      return false;
    }

    return true;
  }

  /**
   * Returns the classpath for this project.
   */
  private XMultimap<File, BuildConfig> compileProject(File projectDir, int depth) {
    checkState(projectDir.isDirectory());

    if (!seenProjects.add(projectDir)) {
      return XMultimap.create();
    }

    BuildConfig buildFile = BuildConfig.getForProject(projectDir);

    Document doc = Jsoup.parse(IO.from(projectDir.child(".classpath")).toString());

    XMultimap<String, Element> classpathEntries = XMultimap.create();
    doc.select("classpathentry").forEach(entry -> {
      classpathEntries.put(entry.attr("kind"), entry);
    });

    XSet<File> myClasspath = XSet.create();
    XMultimap<File, BuildConfig> exportedClasspath = XMultimap.create();

    XList<File> srcPaths = XList.create();

    for (Element e : classpathEntries.get("src")) {
      if (e.hasAttr("combineaccessrules")) {
        // recursively compile this other project
        File dir = findProject(e.attr("path"));
        XMultimap<File, BuildConfig> projectExports = compileProject(dir, depth + 1);
        exportedClasspath.putAll(projectExports);
        myClasspath.addAll(projectExports.keySet());
      } else {
        // here
        File srcPath = projectDir.child(e.attr("path"));
        myClasspath.add(srcPath);
        srcPaths.add(srcPath);
      }
    }

    classpathEntries.get("lib").forEach(e -> {
      File jar = projectDir.child(e.attr("path"));
      myClasspath.add(jar);
      if ("true".equals(e.attr("exported")) || depth == 0 || true) {
        exportedClasspath.put(jar, buildFile);
      }
    });

    if (srcPaths.hasData()) {
      File binDir = srcPaths.get(0).sibling("bin");
      myClasspath.add(binDir);
      exportedClasspath.put(binDir, buildFile);

      if (compile) {
        JavaCompiler.target(binDir)
            .classpath(myClasspath)
            .compile(srcPaths);
      }
    }

    return exportedClasspath;
  }

  private File findProject(String path) {
    File ret = projectDir.parent().child(path);
    if (ret.exists()) {
      return ret;
    }
    ret = projectDir.parent().parent().child(path);
    if (ret.exists()) {
      return ret;
    }
    throw new RuntimeException("Could not find project: " + path);
  }

  public static JarJar project(File projectDir) {
    return new JarJar(projectDir);
  }

  private static void buildJarJar() {
    JarJar.project(File.home("workspace/JarJar"))
        .main("jarjar.JarJarCommandLine")
        .build(File.home("workspace/JarJar/dist/JarJar.jar"));
  }

  public static void main(String[] args) {
    buildJarJar();

    // JarJar.project(File.home("workspace/ender/dev.ender.com"))
    // .main("com.ender.dev.DevServer")
    // .skipCompile()
    // .build(File.downloads("DevServer.jar"));
    Log.debug("Done");
  }

}
