package jarjar;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static ox.util.Utils.propagate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.google.common.base.Stopwatch;

import ox.File;
import ox.IO;
import ox.Log;
import ox.x.XList;
import ox.x.XMultimap;
import ox.x.XSet;

public class JarJar {

  private final File projectDir;
  private String mainClass = "";
  private File outputFile;
  private XSet<File> compiledProjects = XSet.create();
  private boolean verbose = false;

  private JarJar(File projectDir) {
    this.projectDir = checkNotNull(projectDir);
  }

  public JarJar main(String mainClass) {
    this.mainClass = mainClass;
    return this;
  }

  public void build(File outputFile) {
    this.outputFile = outputFile;

    Stopwatch watch = Stopwatch.createStarted();
    XSet<File> classpath = compileProject(projectDir, 0);
    Log.debug("Compiled " + watch);

    watch.reset().start();
    exportJar(classpath);
    Log.debug("JAR created: " + outputFile + " " + watch);
  }

  private void exportJar(XSet<File> classpath) {
    Zipper zipper = new Zipper(outputFile);
    XSet<String> exportedFiles = XSet.create();
    try {
      classpath.forEach(classpathEntry -> {
        try {
          if (classpathEntry.isDirectory()) {
            Log.debug("Copying tree:" + classpathEntry);
            classpathEntry.walkTree(file -> {
              if (!file.isDirectory()) {
                String name = file.getRelativePath(classpathEntry);
                if (verbose) {
                  Log.debug(name);
                }
                zipper.putNextEntry(name, file.inputStream());
              }
            });
          } else {
            copyFilesIntoJar(classpathEntry, zipper, exportedFiles);
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

  private void copyFilesIntoJar(File sourceFile, Zipper zipper, XSet<String> exportedFiles) {
    Log.debug("Copying jar: " + sourceFile);
    Unzipper unzipper = new Unzipper(IO.from(sourceFile).asStream());
    try {
      while (unzipper.hasNext()) {
        if (unzipper.isDirectory()) {
          unzipper.next();
          continue;
        }
        String fileName = unzipper.getName();
        if (fileName.startsWith("META-INF")) {
          unzipper.next();
          continue;
        }
        if (!exportedFiles.add(fileName)) {
          Log.warn("Duplicate file: " + fileName);
          unzipper.next();
          continue;
        }
        if (verbose) {
          Log.debug(fileName);
        }
        zipper.putNextEntry(fileName, unzipper);
      }
    } finally {
      unzipper.finish();
    }
  }

  /**
   * Returns the classpath for this project.
   */
  private XSet<File> compileProject(File projectDir, int depth) {
    checkState(projectDir.isDirectory());

    Document doc = Jsoup.parse(IO.from(projectDir.child(".classpath")).toString());

    XMultimap<String, Element> classpathEntries = XMultimap.create();
    doc.select("classpathentry").forEach(entry -> {
      classpathEntries.put(entry.attr("kind"), entry);
    });

    XSet<File> myClasspath = XSet.create();
    XSet<File> exportedClasspath = XSet.create();

    XList<File> srcPaths = XList.create();

    for (Element e : classpathEntries.get("src")) {
      if (e.hasAttr("combineaccessrules")) {
        // recursively compile this other project
        File dir = findProject(e.attr("path"));
        XSet<File> projectExports = compileProject(dir, depth + 1);
        exportedClasspath.addAll(projectExports);
        myClasspath.addAll(projectExports);
      } else {
        File srcPath = projectDir.child(e.attr("path"));
        myClasspath.add(srcPath);
        srcPaths.add(srcPath);
      }
    }

    classpathEntries.get("lib").forEach(e -> {
      File jar = projectDir.child(e.attr("path"));
      myClasspath.add(jar);
      if ("true".equals(e.attr("exported")) || depth == 0 || true) {
        exportedClasspath.add(jar);
      }
    });

    if (srcPaths.hasData()) {
      File binDir = srcPaths.get(0).sibling("bin");
      myClasspath.add(binDir);
      exportedClasspath.add(binDir);

      if (compiledProjects.add(projectDir)) {
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

  public static void main(String[] args) {
    JarJar.project(File.home("workspace/JarJar"))
        .main("jarjar.JarJarCommandLine")
        .build(File.home("workspace/JarJar/dist/JarJar.jar"));
    Log.debug("Done");
  }

}
