// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.tmaker;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * an annotation processor that processes Java source code at compile time to generate a helper class.
 * This helper class contains static nested classes representing properties
 * (getter methods) of other classes that extend a specific base type FillableTable.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class JavaTableProcessor extends AbstractProcessor {
    private static final String BASE_TYPE = "onl.ycode.stormify.AutoTable";

    @Override
    public Set<String> getSupportedOptions() {
        Set<String> options = new HashSet<>();
        options.add("stormify.meta.class");
        return options;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement baseType = processingEnv.getElementUtils().getTypeElement(BASE_TYPE);
        if (baseType == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Cannot find base type " + BASE_TYPE);
            return false;
        }
        String className = processingEnv.getOptions().getOrDefault("stormify.meta.class", "tables.T");
        int lastDot = className.lastIndexOf('.');
        if (lastDot == -1) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Invalid class name " + className);
            return false;
        }
        String reqPackage = className.substring(0, lastDot);
        String reqClass = className.substring(lastDot + 1);

        PrintWriter tfile;
        try {
            tfile = new PrintWriter(processingEnv.getFiler().createSourceFile(reqPackage + "." + reqClass).openWriter());
        } catch (IOException e) {
            return false;
        }

        Map<String, Collection<String>> properties = new TreeMap<>();
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                TypeElement typeElement = (TypeElement) element;
                if (isFillableTable(baseType, typeElement)) {
                    // Collect methods or properties
                    Collection<String> methods = collectProperties(typeElement);
                    if (methods != null)
                        properties.put(typeElement.getQualifiedName().toString(), methods);
                    // Write to shared location
                }
            }
        }
        writeToSharedLocation(properties, reqPackage, reqClass, tfile);
        tfile.close();
        return false;
    }

    private boolean isFillableTable(TypeElement baseType, TypeElement currentElement) {
        // Check if the class extends FillableTable
        return processingEnv.getTypeUtils().isSubtype(currentElement.asType(), baseType.asType());
    }

    private Collection<String> collectProperties(TypeElement typeElement) {
        // First gather fields that start with "is", in case this the name of the property
        Collection<String> fields = new TreeSet<>();
        for (Element enclosed : typeElement.getEnclosedElements())
            if (enclosed.getKind() == ElementKind.FIELD) {
                String name = enclosed.getSimpleName().toString();
                if (name.startsWith("is"))
                    fields.add(name);
            }
        Collection<String> methods = new TreeSet<>();
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                String name = method.getSimpleName().toString();
                if (method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID && !name.contains("$") && !name.equals("getClass")) {
                    if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3)))
                        methods.add(name.substring(3, 4).toLowerCase() + name.substring(4));
                    else if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
                        if (fields.contains(name))
                            methods.add(name);
                        else
                            methods.add(name.substring(2, 3).toLowerCase() + name.substring(3));
                    }
                }
            }
        }
        return methods.isEmpty() ? null : methods;
    }


    private void writeToSharedLocation(Map<String, Collection<String>> properties, String reqPackage, String reqClass, PrintWriter out) {
        if (properties.isEmpty())
            return;
        out.println("package " + reqPackage + ";");
        out.println();
        out.println("@SuppressWarnings(\"unused\")");
        out.println("public final class " + reqClass + " {");
        out.println("    private " + reqClass + "() {}");
        boolean firstEntry = true;
        for (Map.Entry<String, Collection<String>> entry : properties.entrySet()) {
            if (firstEntry) firstEntry = false;
            else out.println();
            out.println("    public static final class " + entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1) + " {");
            out.println("        private " + entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1) + "() {}");
            out.println("        // public static final String __classname = \"" + entry.getKey() + "\";");
            for (String property : entry.getValue())
                out.println("        public static final String " + property + " = \"" + property + "\";");
            out.println("    }");
        }
        out.println("}");
    }
}
