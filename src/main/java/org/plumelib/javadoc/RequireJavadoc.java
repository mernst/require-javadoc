package org.plumelib.javadoc;

import static com.github.javaparser.utils.PositionUtils.sortByBeginPosition;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.plumelib.options.Option;
import org.plumelib.options.Options;

/**
 * A program that issues an error for any class, constructor, method, or field that lacks a Javadoc
 * comment. Does not issue a warning for methods annotated with {@code @Override}. See documentation
 * at <a
 * href="https://github.com/plume-lib/require-javadoc">https://github.com/plume-lib/require-javadoc</a>.
 */
public class RequireJavadoc {

  /** Matches name of file or directory where no problems should be reported. */
  @Option("Don't check files or directories whose pathname matches the regex")
  public @MonotonicNonNull Pattern exclude = null;

  // TODO: It would be nice to support matching fully-qualified class names, but matching
  // packages will have to do for now.
  /**
   * Matches simple name of class/constructor/method/field, or full package name, where no problems
   * should be reported.
   */
  @Option("Don't report problems in Java elements whose name matches the regex")
  public @MonotonicNonNull Pattern dont_require = null;

  /** If true, don't check elements with private access. */
  @Option("Don't report problems in elements with private access")
  public boolean dont_require_private;

  /**
   * If true, don't check constructors with zero formal parameters. These are sometimes called
   * "default constructors", though that term means a no-argument constructor that the compiler
   * synthesized when the programmer didn't write one.
   */
  @Option("Don't report problems in constructors with zero formal parameters")
  public boolean dont_require_noarg_constructor;

  /**
   * If true, don't check trivial getters and setters.
   *
   * <p>Trivial getters and setters are of the form:
   *
   * <pre>{@code
   * SomeType getFoo() {
   *   return foo;
   * }
   *
   * SomeType foo() {
   *   return foo;
   * }
   *
   * void setFoo(SomeType foo) {
   *   this.foo = foo;
   * }
   *
   * boolean hasFoo() {
   *   return foo;
   * }
   *
   * boolean isFoo() {
   *   return foo;
   * }
   *
   * boolean notFoo() {
   *   return !foo;
   * }
   * }</pre>
   */
  @Option("Don't report problems in trivial getters and setters")
  public boolean dont_require_trivial_properties;

  /** If true, don't check type declarations: classes, interfaces, enums, annotations, records. */
  @Option("Don't report problems in type declarations")
  public boolean dont_require_type;

  /** If true, don't check fields. */
  @Option("Don't report problems in fields")
  public boolean dont_require_field;

  /** If true, don't check methods, constructors, and annotation members. */
  @Option("Don't report problems in methods and constructors")
  public boolean dont_require_method;

  /** If true, warn if any package lacks a package-info.java file. */
  @Option("Require package-info.java file to exist")
  public boolean require_package_info;

  /**
   * If true, print filenames relative to working directory. Setting this only has an effect if the
   * command-line arguments were absolute pathnames, or no command-line arguments were supplied.
   */
  @Option("Report relative rather than absolute filenames")
  public boolean relative = false;

  /** If true, output debug information. */
  @Option("Print diagnostic information")
  public boolean verbose = false;

  /** All the errors this program will report. */
  private List<String> errors = new ArrayList<>();

  /** The Java files to be checked. */
  private List<Path> javaFiles = new ArrayList<Path>();

  /** The current working directory, for making relative pathnames. */
  private Path workingDirRelative = Paths.get("");

  /** The current working directory, for making relative pathnames. */
  private Path workingDirAbsolute = Paths.get("").toAbsolutePath();

  /**
   * The main entry point for the require-javadoc program. See documentation at <a
   * href="https://github.com/plume-lib/require-javadoc">https://github.com/plume-lib/require-javadoc</a>.
   *
   * @param args the command-line arguments; see the README.md file
   */
  public static void main(String[] args) {
    RequireJavadoc rj = new RequireJavadoc();
    Options options =
        new Options(
            "java org.plumelib.javadoc.RequireJavadoc [options] [directory-or-file ...]", rj);
    String[] remainingArgs = options.parse(true, args);

    rj.setJavaFiles(remainingArgs);

    List<String> exceptionsThrown = new ArrayList<>();

    for (Path javaFile : rj.javaFiles) {
      if (rj.verbose) {
        System.out.println("Checking " + javaFile);
      }
      try {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        StaticJavaParser.setConfiguration(parserConfiguration);
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        RequireJavadocVisitor visitor = rj.new RequireJavadocVisitor(javaFile);
        visitor.visit(cu, null);
      } catch (IOException e) {
        exceptionsThrown.add("Problem while reading " + javaFile + ": " + e.getMessage());
      } catch (ParseProblemException e) {
        exceptionsThrown.add("Problem while parsing " + javaFile + ": " + e.getMessage());
      }
    }
    for (String error : rj.errors) {
      System.out.println(error);
    }

    if (!exceptionsThrown.isEmpty()) {
      for (String exceptionThrown : exceptionsThrown) {
        System.out.println(exceptionThrown);
      }
      System.exit(2);
    }

    System.exit(rj.errors.isEmpty() ? 0 : 1);
  }

  /** Creates a new RequireJavadoc instance. */
  private RequireJavadoc() {}

  /**
   * Set the Java files to be processed from the command-line arguments.
   *
   * @param args the directories and files listed on the command line
   */
  @SuppressWarnings({
    "lock:methodref.receiver", // Comparator.comparing
    "lock:type.arguments.not.inferred" // Comparator.comparing
  })
  private void setJavaFiles(String[] args) {
    if (args.length == 0) {
      args = new String[] {workingDirAbsolute.toString()};
    }

    FileVisitor<Path> walker = new JavaFilesVisitor();

    for (String arg : args) {
      if (shouldExclude(arg)) {
        continue;
      }
      Path p = Paths.get(arg);
      File f = p.toFile();
      if (!f.exists()) {
        System.out.println("File not found: " + f);
        System.exit(2);
      }
      if (f.isDirectory()) {
        try {
          Files.walkFileTree(p, walker);
        } catch (IOException e) {
          System.out.println("Problem while reading " + f + ": " + e.getMessage());
          System.exit(2);
        }
      } else {
        javaFiles.add(Paths.get(arg));
      }
    }

    javaFiles.sort(Comparator.comparing(Object::toString));

    Set<Path> missingPackageInfoFiles = new LinkedHashSet<>();
    if (require_package_info) {
      for (Path javaFile : javaFiles) {
        @SuppressWarnings("nullness:assignment") // the file is not "/", so getParent() is non-null
        @NonNull Path javaFileParent = javaFile.getParent();
        // Java 11 has Path.of() instead of creating a new File.
        Path packageInfo = javaFileParent.resolve(new File("package-info.java").toPath());
        if (!javaFiles.contains(packageInfo)) {
          missingPackageInfoFiles.add(packageInfo);
        }
      }
      for (Path packageInfo : missingPackageInfoFiles) {
        errors.add("missing package documentation: no file " + packageInfo);
      }
    }
  }

  /** Collects files into the {@link #javaFiles} variable. */
  private class JavaFilesVisitor extends SimpleFileVisitor<Path> {

    /** Create a new JavaFilesVisitor. */
    public JavaFilesVisitor() {}

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
      if (attr.isRegularFile() && file.toString().endsWith(".java")) {
        if (!shouldExclude(file)) {
          javaFiles.add(file);
        }
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attr) {
      if (shouldExclude(dir)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      if (exc != null) {
        System.out.println("Problem visiting " + dir + ": " + exc.getMessage());
        System.exit(2);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      if (exc != null) {
        System.out.println("Problem visiting " + file + ": " + exc.getMessage());
        System.exit(2);
      }
      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * Return true if the given Java element should not be checked, based on the {@code
   * --dont-require} command-line argument.
   *
   * @param name the name of a Java element. It is a simple name, except for packages.
   * @return true if no warnings should be issued about the element
   */
  private boolean shouldNotRequire(String name) {
    if (dont_require == null) {
      return false;
    }
    boolean result = dont_require.matcher(name).find();
    if (verbose) {
      System.out.printf("shouldNotRequire(%s) => %s%n", name, result);
    }
    return result;
  }

  /**
   * Return true if the given file or directory should be skipped, based on the {@code --exclude}
   * command-line argument.
   *
   * @param fileName the name of a Java file or directory
   * @return true if the file or directory should be skipped
   */
  private boolean shouldExclude(String fileName) {
    if (exclude == null) {
      return false;
    }
    boolean result = exclude.matcher(fileName).find();
    if (verbose) {
      System.out.printf("shouldExclude(%s) => %s%n", fileName, result);
    }
    return result;
  }

  /**
   * Return true if the given file or directory should be skipped, based on the {@code --exclude}
   * command-line argument.
   *
   * @param path a Java file or directory
   * @return true if the file or directory should be skipped
   */
  private boolean shouldExclude(Path path) {
    return shouldExclude(path.toString());
  }

  /** A property method's return type. */
  private enum ReturnType {
    /** The return type is void. */
    VOID,
    /** The return type is boolean. */
    BOOLEAN,
    /** The return type is non-void. */
    NON_VOID;
  }

  /** The type of property method: a getter or setter. */
  private enum PropertyKind {
    /** A method of the form {@code SomeType getFoo()}. */
    GETTER("get", 0, ReturnType.NON_VOID),
    /** A method of the form {@code SomeType foo()}. */
    GETTER_NO_PREFIX("", 0, ReturnType.NON_VOID),
    /** A method of the form {@code boolean hasFoo()}. */
    GETTER_HAS("has", 0, ReturnType.BOOLEAN),
    /** A method of the form {@code boolean isFoo()}. */
    GETTER_IS("is", 0, ReturnType.BOOLEAN),
    /** A method of the form {@code boolean notFoo()}. */
    GETTER_NOT("not", 0, ReturnType.BOOLEAN),
    /** A method of the form {@code void setFoo(SomeType arg)}. */
    SETTER("set", 1, ReturnType.VOID),
    /** Not a getter or setter. */
    NOT_PROPERTY("", -1, ReturnType.VOID);

    /** The prefix for the method name: "get", "", "has", "is", "not", or "set". */
    final String prefix;

    /** The number of required formal parameters: 0 or 1. */
    final int requiredParams;

    /** The return type. */
    final ReturnType returnType;

    /**
     * Create a new PropertyKind.
     *
     * @param prefix the prefix for the method name: "get", "has", "is", "not", or "set"
     * @param requiredParams the number of required formal parameters: 0 or 1
     * @param returnType the return type
     */
    PropertyKind(String prefix, int requiredParams, ReturnType returnType) {
      this.prefix = prefix;
      this.requiredParams = requiredParams;
      this.returnType = returnType;
    }

    /**
     * Returns true if this is a getter.
     *
     * @return true if this is a getter
     */
    boolean isGetter() {
      return this != SETTER;
    }

    /**
     * Return the PropertyKind for the given method, or null if it isn't a property accessor method.
     *
     * @param md the method to check
     * @return the PropertyKind for the given method, or null
     */
    static PropertyKind fromMethodDeclaration(MethodDeclaration md) {
      String methodName = md.getNameAsString();
      if (methodName.startsWith("get")) {
        return GETTER;
      } else if (methodName.startsWith("has")) {
        return GETTER_HAS;
      } else if (methodName.startsWith("is")) {
        return GETTER_IS;
      } else if (methodName.startsWith("not")) {
        return GETTER_NOT;
      } else if (methodName.startsWith("set")) {
        return SETTER;
      } else {
        return GETTER_NO_PREFIX;
      }
    }
  }

  /**
   * Return true if this method declaration is a trivial getter or setter.
   *
   * <ul>
   *   <li>A trivial getter is named {@code getFoo}, {@code foo}, {@code hasFoo}, {@code isFoo}, or
   *       {@code notFoo}, has no formal parameters, and has a body of the form {@code return foo}
   *       or {@code return this.foo} (except for {@code notFoo}, in which case the body is
   *       negated).
   *   <li>A trivial setter is named {@code setFoo}, has one formal parameter named {@code foo}, and
   *       has a body of the form {@code this.foo = foo}.
   * </ul>
   *
   * @param md the method to check
   * @return true if this method is a trivial getter or setter
   */
  private boolean isTrivialGetterOrSetter(MethodDeclaration md) {
    PropertyKind kind = PropertyKind.fromMethodDeclaration(md);
    if (kind != PropertyKind.GETTER_NO_PREFIX) {
      if (isTrivialGetterOrSetter(md, kind)) {
        return true;
      }
    }
    return isTrivialGetterOrSetter(md, PropertyKind.GETTER_NO_PREFIX);
  }

  /**
   * Return true if this method declaration is a trivial getter or setter of the given kind.
   *
   * @see #isTrivialGetterOrSetter(MethodDeclaration)
   * @param md the method to check
   * @param propertyKind the kind of property
   * @return true if this method is a trivial getter or setter
   */
  private boolean isTrivialGetterOrSetter(MethodDeclaration md, PropertyKind propertyKind) {
    String propertyName = propertyName(md, propertyKind);
    return propertyName != null
        && hasCorrectSignature(md, propertyKind, propertyName)
        && hasCorrectBody(md, propertyKind, propertyName);
  }

  /**
   * Returns the name of the property, if the method is a getter or setter of the given kind.
   * Otherwise returns null.
   *
   * <p>Examines the method's name, but not its signature or body. Also does not check that the
   * given property name corresponds to an existing field.
   *
   * @param md the method to test
   * @param propertyKind the type of property method
   * @return the name of the property, or null
   */
  private @Nullable String propertyName(MethodDeclaration md, PropertyKind propertyKind) {
    String methodName = md.getNameAsString();
    assert methodName.startsWith(propertyKind.prefix);
    @SuppressWarnings("index") // https://github.com/typetools/checker-framework/issues/5201
    String upperCamelCaseProperty = methodName.substring(propertyKind.prefix.length());
    if (upperCamelCaseProperty.length() == 0) {
      return null;
    }
    if (propertyKind == PropertyKind.GETTER_NO_PREFIX) {
      return upperCamelCaseProperty;
    } else if (!Character.isUpperCase(upperCamelCaseProperty.charAt(0))) {
      return null;
    } else {
      return ""
          + Character.toLowerCase(upperCamelCaseProperty.charAt(0))
          + upperCamelCaseProperty.substring(1);
    }
  }

  /**
   * Returns true if the signature of the given method is a property accessor of the given kind.
   *
   * @param md the method
   * @param propertyKind the kind of property
   * @param propertyName the name of the property
   * @return true if the body of the given method is a property accessor
   */
  private boolean hasCorrectSignature(
      MethodDeclaration md, PropertyKind propertyKind, String propertyName) {
    NodeList<Parameter> parameters = md.getParameters();
    if (parameters.size() != propertyKind.requiredParams) {
      return false;
    }
    if (parameters.size() == 1) {
      Parameter parameter = parameters.get(0);
      if (!parameter.getNameAsString().equals(propertyName)) {
        return false;
      }
    }
    // Check presence/absence of return type. (The Java compiler will verify
    // that the type is consistent with the method body.)
    Type returnType = md.getType();
    switch (propertyKind.returnType) {
      case VOID:
        if (!returnType.isVoidType()) {
          return false;
        }
        break;
      case BOOLEAN:
        if (!returnType.equals(PrimitiveType.booleanType())) {
          return false;
        }
        break;
      case NON_VOID:
        if (returnType.isVoidType()) {
          return false;
        }
        break;
      default:
        throw new Error("Unexpected enum value " + propertyKind.returnType);
    }
    return true;
  }

  /**
   * Returns true if the body of the given method is a property accessor of the given kind.
   *
   * @param md the method
   * @param propertyKind the kind of property
   * @param propertyName the name of the property
   * @return true if the body of the given method is a property accessor
   */
  private boolean hasCorrectBody(
      MethodDeclaration md, PropertyKind propertyKind, String propertyName) {
    Statement statement = getOnlyStatement(md);
    if (propertyKind.isGetter()) {
      if (!(statement instanceof ReturnStmt)) {
        return false;
      }
      Optional<Expression> optReturnExpr = ((ReturnStmt) statement).getExpression();
      if (!optReturnExpr.isPresent()) {
        return false;
      }
      Expression returnExpr = optReturnExpr.get();
      // Does not handle parentheses.
      if (propertyKind == PropertyKind.GETTER_NOT) {
        if (!(returnExpr instanceof UnaryExpr)) {
          return false;
        }
        UnaryExpr unary = (UnaryExpr) returnExpr;
        if (unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
          return false;
        }
        returnExpr = unary.getExpression();
      }
      String returnName;
      // Does not handle parentheses.
      if (returnExpr instanceof NameExpr) {
        returnName = ((NameExpr) returnExpr).getNameAsString();
      } else if (returnExpr instanceof FieldAccessExpr) {
        FieldAccessExpr fa = (FieldAccessExpr) returnExpr;
        Expression receiver = fa.getScope();
        if (!(receiver instanceof ThisExpr)) {
          return false;
        }
        returnName = fa.getNameAsString();
      } else {
        return false;
      }
      if (!returnName.equals(propertyName)) {
        return false;
      }
      return true;
    } else if (propertyKind == PropertyKind.SETTER) {
      if (!(statement instanceof ExpressionStmt)) {
        return false;
      }
      Expression expr = ((ExpressionStmt) statement).getExpression();
      if (!(expr instanceof AssignExpr)) {
        return false;
      }
      AssignExpr assignExpr = (AssignExpr) expr;
      Expression target = assignExpr.getTarget();
      if (!(target instanceof FieldAccessExpr)) {
        return false;
      }
      FieldAccessExpr fa = (FieldAccessExpr) target;
      Expression receiver = fa.getScope();
      if (!(receiver instanceof ThisExpr)) {
        return false;
      }
      if (!fa.getNameAsString().equals(propertyName)) {
        return false;
      }
      if (assignExpr.getOperator() != AssignExpr.Operator.ASSIGN) {
        return false;
      }
      Expression value = assignExpr.getValue();
      if (!(value instanceof NameExpr
          && ((NameExpr) value).getNameAsString().equals(propertyName))) {
        return false;
      }
      return true;
    } else {
      throw new Error("unexpected PropertyKind " + propertyKind);
    }
  }

  /**
   * If the body contains exactly one statement, returns it. Otherwise, returns null.
   *
   * @param md a method declaration
   * @return its sole statement, or null
   */
  private @Nullable Statement getOnlyStatement(MethodDeclaration md) {
    Optional<BlockStmt> body = md.getBody();
    if (!body.isPresent()) {
      return null;
    }
    NodeList<Statement> statements = body.get().getStatements();
    if (statements.size() != 1) {
      return null;
    }
    return statements.get(0);
  }

  /** Visits an AST and collects warnings about missing Javadoc. */
  private class RequireJavadocVisitor extends VoidVisitorAdapter<Void> {

    /** The file being visited. Used for constructing error messages. */
    private Path filename;

    /**
     * Create a new RequireJavadocVisitor.
     *
     * @param filename the file being visited; used for diagnostic messages
     */
    public RequireJavadocVisitor(Path filename) {
      this.filename = filename;
    }

    /**
     * Return a string stating that documentation is missing on the given construct.
     *
     * @param node a Java language construct (class, constructor, method, field, etc.)
     * @param simpleName the construct's simple name, used in diagnostic messages
     * @return an error message for the given construct
     */
    private String errorString(Node node, String simpleName) {
      Optional<Range> range = node.getRange();
      if (range.isPresent()) {
        Position begin = range.get().begin;
        Path path =
            (relative
                ? (filename.isAbsolute() ? workingDirAbsolute : workingDirRelative)
                    .relativize(filename)
                : filename);
        return String.format(
            "%s:%d:%d: missing documentation for %s", path, begin.line, begin.column, simpleName);
      } else {
        return "missing documentation for " + simpleName;
      }
    }

    @Override
    public void visit(CompilationUnit cu, Void ignore) {
      Optional<PackageDeclaration> opd = cu.getPackageDeclaration();
      if (opd.isPresent()) {
        String packageName = opd.get().getName().asString();
        if (shouldNotRequire(packageName)) {
          return;
        }
        Optional<String> optTypeName = cu.getPrimaryTypeName();
        if (optTypeName.isPresent()
            && optTypeName.get().equals("package-info")
            && !hasJavadocComment(opd.get())
            && !hasJavadocComment(cu)) {
          errors.add(errorString(opd.get(), packageName));
        }
      }
      if (verbose) {
        System.out.printf("Visiting compilation unit%n");
      }
      super.visit(cu, null);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cd, Void ignore) {
      if (dont_require_private && cd.isPrivate()) {
        return;
      }
      String name = cd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting type %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(cd)) {
        errors.add(errorString(cd, name));
      }
      super.visit(cd, null);
    }

    @Override
    public void visit(ConstructorDeclaration cd, Void ignore) {
      if (dont_require_private && cd.isPrivate()) {
        return;
      }
      if (dont_require_noarg_constructor && cd.getParameters().size() == 0) {
        return;
      }
      String name = cd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting constructor %s%n", name);
      }
      if (!dont_require_method && !hasJavadocComment(cd)) {
        errors.add(errorString(cd, name));
      }
      super.visit(cd, null);
    }

    @Override
    public void visit(MethodDeclaration md, Void ignore) {
      if (dont_require_private && md.isPrivate()) {
        return;
      }
      if (dont_require_trivial_properties && isTrivialGetterOrSetter(md)) {
        if (verbose) {
          System.out.printf("skipping trivial property method %s%n", md.getNameAsString());
        }
        return;
      }
      String name = md.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting method %s%n", md.getName());
      }
      if (!dont_require_method && !isOverride(md) && !hasJavadocComment(md)) {
        errors.add(errorString(md, name));
      }
      super.visit(md, null);
    }

    @Override
    public void visit(FieldDeclaration fd, Void ignore) {
      if (dont_require_private && fd.isPrivate()) {
        return;
      }
      // True if shouldNotRequire is false for at least one of the fields
      boolean shouldRequire = false;
      if (verbose) {
        System.out.printf("Visiting field %s%n", fd.getVariables().get(0).getName());
      }
      boolean hasJavadocComment = hasJavadocComment(fd);
      for (VariableDeclarator vd : fd.getVariables()) {
        String name = vd.getNameAsString();
        // TODO: Also check the type of the serialVersionUID variable.
        if (name.equals("serialVersionUID")) {
          continue;
        }
        if (shouldNotRequire(name)) {
          continue;
        }
        shouldRequire = true;
        if (!dont_require_field && !hasJavadocComment) {
          errors.add(errorString(vd, name));
        }
      }
      if (shouldRequire) {
        super.visit(fd, null);
      }
    }

    @Override
    public void visit(EnumDeclaration ed, Void ignore) {
      if (dont_require_private && ed.isPrivate()) {
        return;
      }
      String name = ed.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting enum %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(ed)) {
        errors.add(errorString(ed, name));
      }
      super.visit(ed, null);
    }

    @Override
    public void visit(EnumConstantDeclaration ecd, Void ignore) {
      String name = ecd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting enum constant %s%n", name);
      }
      if (!dont_require_field && !hasJavadocComment(ecd)) {
        errors.add(errorString(ecd, name));
      }
      super.visit(ecd, null);
    }

    @Override
    public void visit(AnnotationDeclaration ad, Void ignore) {
      if (dont_require_private && ad.isPrivate()) {
        return;
      }
      String name = ad.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting annotation %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(ad)) {
        errors.add(errorString(ad, name));
      }
      super.visit(ad, null);
    }

    @Override
    public void visit(AnnotationMemberDeclaration amd, Void ignore) {
      String name = amd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting annotation member %s%n", name);
      }
      if (!dont_require_method && !hasJavadocComment(amd)) {
        errors.add(errorString(amd, name));
      }
      super.visit(amd, null);
    }

    @Override
    public void visit(RecordDeclaration rd, Void ignore) {
      if (dont_require_private && rd.isPrivate()) {
        return;
      }
      String name = rd.getNameAsString();
      if (shouldNotRequire(name)) {
        return;
      }
      if (verbose) {
        System.out.printf("Visiting record %s%n", name);
      }
      if (!dont_require_type && !hasJavadocComment(rd)) {
        errors.add(errorString(rd, name));
      }
      // Don't warn about record parameters, because Javadoc requires @param for them in the record
      // declaration itself.
      super.visit(rd, null);
    }

    /**
     * Return true if this method is annotated with {@code @Override}.
     *
     * @param md the method to check for an {@code @Override} annotation
     * @return true if this method is annotated with {@code @Override}
     */
    private boolean isOverride(MethodDeclaration md) {
      for (AnnotationExpr anno : md.getAnnotations()) {
        String annoName = anno.getName().toString();
        if (annoName.equals("Override") || annoName.equals("java.lang.Override")) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Return true if this node has a Javadoc comment.
   *
   * @param n the node to check for a Javadoc comment
   * @return true if this node has a Javadoc comment
   */
  private boolean hasJavadocComment(Node n) {
    if (n instanceof NodeWithJavadoc && ((NodeWithJavadoc<?>) n).hasJavaDocComment()) {
      return true;
    }
    List<Comment> orphans = new ArrayList<>();
    getOrphanCommentsBeforeThisChildNode(n, orphans);
    for (Comment orphan : orphans) {
      if (orphan.isJavadocComment()) {
        return true;
      }
    }
    Optional<Comment> oc = n.getComment();
    if (oc.isPresent()
        && (oc.get().isJavadocComment() || oc.get().getContent().startsWith("/**"))) {
      return true;
    }
    return false;
  }

  /**
   * Get "orphan comments": comments before the comment before this node. For example, in
   *
   * <pre>{@code
   * /** ... *}{@code /
   * // text 1
   * // text 2
   * void m() { ... }
   * }</pre>
   *
   * <p>the Javadoc comment and {@code // text 1} are an orphan comment, and only {@code // text2}
   * is associated with the method.
   *
   * @param node the node whose orphan comments to collect
   * @param result the list to add orphan comments to. Is side-effected by this method. The
   *     implementation uses this to minimize the diffs against upstream.
   */
  @SuppressWarnings({
    "JdkObsolete", // for LinkedList
    "interning:not.interned", // element of a list
    "ReferenceEquality",
  })
  // This implementation is from Randoop's `Minimize.java` file, and before that from JavaParser's
  // PrettyPrintVisitor.printOrphanCommentsBeforeThisChildNode.  The JavaParser maintainers refuse
  // to provide such functionality in JavaParser proper.
  private static void getOrphanCommentsBeforeThisChildNode(final Node node, List<Comment> result) {
    if (node instanceof Comment) {
      return;
    }

    Node parent = node.getParentNode().orElse(null);
    if (parent == null) {
      return;
    }
    List<Node> everything = new LinkedList<>(parent.getChildNodes());
    sortByBeginPosition(everything);
    int positionOfTheChild = -1;
    for (int i = 0; i < everything.size(); i++) {
      if (everything.get(i) == node) {
        positionOfTheChild = i;
      }
    }
    if (positionOfTheChild == -1) {
      throw new AssertionError("I am not a child of my parent.");
    }
    int positionOfPreviousChild = -1;
    for (int i = positionOfTheChild - 1; i >= 0 && positionOfPreviousChild == -1; i--) {
      if (!(everything.get(i) instanceof Comment)) {
        positionOfPreviousChild = i;
      }
    }
    for (int i = positionOfPreviousChild + 1; i < positionOfTheChild; i++) {
      Node nodeToPrint = everything.get(i);
      if (!(nodeToPrint instanceof Comment)) {
        throw new RuntimeException(
            "Expected comment, instead "
                + nodeToPrint.getClass()
                + ". Position of previous child: "
                + positionOfPreviousChild
                + ", position of child "
                + positionOfTheChild);
      }
      result.add((Comment) nodeToPrint);
    }
  }
}
