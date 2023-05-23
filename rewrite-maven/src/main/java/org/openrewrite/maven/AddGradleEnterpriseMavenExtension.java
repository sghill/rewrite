/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.maven;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Value;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.util.DefaultXmlPrettyPrinter;
import lombok.*;
import org.intellij.lang.annotations.Language;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.xml.AddToTagVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.maven.internal.MavenXmlMapper;
import org.openrewrite.style.GeneralFormatStyle;
import org.openrewrite.xml.*;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;
import static org.openrewrite.PathUtils.separatorsToUnix;


@Value
@EqualsAndHashCode(callSuper = true)
public class AddGradleEnterpriseMavenExtension extends ScanningRecipe<AddGradleEnterpriseMavenExtension.Accumulator> {
    private static final String GRADLE_ENTERPRISE_MAVEN_EXTENSION_ARTIFACT_ID = "gradle-enterprise-maven-extension";
    private static final String EXTENSIONS_XML_PATH = ".mvn/extensions.xml";
    private static final String GRADLE_ENTERPRISE_XML_PATH = ".mvn/gradle-enterprise.xml";

    @Language("xml")
    private static final String EXTENSIONS_XML_FORMAT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<extensions>\n" +
            "</extensions>";

    @Language("xml")
    private static final String ENTERPRISE_TAG_FORMAT = "<extension>\n" +
            "  <groupId>com.gradle</groupId>\n" +
            "  <artifactId>gradle-enterprise-maven-extension</artifactId>\n" +
            "  <version>%s</version>\n" +
            "</extension>";

    @Language("xml")
    private static final String ENTERPRISE_TAG_FORMAT_WITHOUT_VERSION = "<extension>\n" +
            "  <groupId>com.gradle</groupId>\n" +
            "  <artifactId>gradle-enterprise-maven-extension</artifactId>\n" +
            "</extension>";

    @Language("xml")
    private static final String GRADLE_ENTERPRISE_XML_FORMAT = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>\n" +
            "<gradleEnterprise\n" +
            "    xmlns=\"https://www.gradle.com/gradle-enterprise-maven\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            "    xsi:schemaLocation=\"https://www.gradle.com/gradle-enterprise-maven" +
            " https://www.gradle.com/schema/gradle-enterprise-maven.xsd\">\n" +
            "  <server>\n" +
            "    <url>%s</url>\n" +
            "    <allowUntrusted>%b</allowUntrusted>\n" +
            "  </server>\n" +
            "  <buildScan>\n" +
            "    <backgroundBuildScanUpload>false</backgroundBuildScanUpload>\n" +
            "    <publish>ALWAYS</publish>\n" +
            "  </buildScan>\n" +
            "</gradleEnterprise>";

    @Option(displayName = "Plugin version",
            description = "An exact version number or node-style semver selector used to select the gradle-enterprise-maven-extension version.",
            example = "1.x")
    @Nullable
    String version;

    @Option(displayName = "Server URL",
            description = "The URL of the Gradle Enterprise server.",
            example = "https://scans.gradle.com/")
    String server;

    @Option(displayName = "Allow untrusted server",
            description = "When set to `true` the extension will be configured to allow unencrypted http connections with the server. " +
                    "If set to `false` or omitted, the extension will refuse to communicate without transport layer security enabled.",
            required = false,
            example = "true")
    @Nullable
    Boolean allowUntrustedServer;

    @Option(displayName = "Capture goal input files",
            description = "When set to `true` the extension will capture additional information about the inputs to Maven goals. " +
                    "This increases the size of build scans, but is useful for diagnosing issues with goal caching. ",
            required = false,
            example = "true")
    @Nullable
    Boolean captureGoalInputFiles;

    @Option(displayName = "Upload in background",
            description = "When set to `false` the extension will not upload build scan in the background. " +
                    "By default, build scans are uploaded in the background after the build has finished to avoid blocking the build process.",
            required = false,
            example = "false")
    @Nullable
    Boolean uploadInBackground;

    @Option(displayName = "Publish Criteria",
            description = "When set to `always` the extension will publish build scans of every single build. " +
                    "This is the default behavior when omitted." +
                    "When set to `failure` the extension will only publish build scans when the build fails. " +
                    "When set to `demand` the extension will only publish build scans when explicitly requested.",
            required = false,
            valid = {"always", "failure", "demand"},
            example = "true")
    @Nullable
    PublishCriteria publishCriteria;

    public enum PublishCriteria {
        Always("ALWAYS"),
        Failure("ON_FAILURE"),
        Demand("ON_DEMAND");

        private final String xmlName;

        PublishCriteria(String xmlName) {
            this.xmlName = xmlName;
        }
    }

    @Override
    public String getDisplayName() {
        return "Add Gradle Enterprise Maven Extension to maven projects";
    }

    @Override
    public String getDescription() {
        return "To integrate gradle enterprise maven extension into maven projects, ensure that the " +
                "`gradle-enterprise-maven-extension` is added to the `.mvn/extensions.xml` file if not already present. " +
                "Additionally, configure the extension by adding the `.mvn/gradle-enterprise.xml` configuration file.";
    }

    @Data
    static class Accumulator {
        boolean isMavenProject;
        boolean useCRLFNewLines;
        boolean extensionsXmlExists;
        boolean gradleEnterpriseXmlExists;
    }

    @Override
    public Accumulator getInitialValue() {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(@Nullable Tree tree, ExecutionContext executionContext) {
                if(!(tree instanceof SourceFile)) {
                    return tree;
                }
                SourceFile sourceFile = (SourceFile) tree;
                String sourcePath = separatorsToUnix(sourceFile.getSourcePath().toString());
                switch (sourcePath) {
                    case "pom.xml":
                        acc.setMavenProject(true);
                        acc.setUseCRLFNewLines(sourceFile.getStyle(GeneralFormatStyle.class, new GeneralFormatStyle(false))
                                .isUseCRLFNewLines());
                        break;
                    case EXTENSIONS_XML_PATH:
                        acc.setExtensionsXmlExists(true);
                        break;
                    case GRADLE_ENTERPRISE_XML_PATH:
                        acc.setGradleEnterpriseXmlExists(true);
                        break;
                    default:
                        break;
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (!acc.isMavenProject() || acc.isGradleEnterpriseXmlExists()) {
            return emptyList();
        }
        List<SourceFile> newFiles = new ArrayList<>(2);
        newFiles.add(createNewXml(GRADLE_ENTERPRISE_XML_PATH, gradleEnterpriseConfiguration(acc.isUseCRLFNewLines())));

        if(!acc.isExtensionsXmlExists()) {
            newFiles.add(createNewXml(EXTENSIONS_XML_PATH, EXTENSIONS_XML_FORMAT));
        }

        return newFiles;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new XmlVisitor<ExecutionContext>() {
            @Override
            public Xml visitDocument(Xml.Document document, ExecutionContext ctx) {
                if(!PathUtils.equalIgnoringSeparators(document.getSourcePath().toString(), EXTENSIONS_XML_PATH)) {
                    return document;
                }
                return addEnterpriseExtension(document, ctx);
            }
        };
    }

    @JacksonXmlRootElement(localName = "gradleEnterprise")
    @Value
    private static class GradleEnterpriseConfiguration {
        ServerConfiguration server;
        @Nullable
        BuildScanConfiguration buildScan;
    }

    @Value
    private static class ServerConfiguration {
        String url;
        @Nullable
        Boolean allowUntrusted;
    }

    @Value
    private static class BuildScanConfiguration {
        @Nullable
        Boolean backgroundBuildScanUpload;
        @Nullable
        String publish;
        @Nullable
        Capture capture;
    }

    @Value
    private static class Capture {
        Boolean goalInputFiles;
    }

    private String gradleEnterpriseConfiguration(boolean useCRLFNewLines) {
        BuildScanConfiguration buildScanConfiguration = buildScanConfiguration();
        ServerConfiguration serverConfiguration = new ServerConfiguration(server, allowUntrustedServer);
        try {
            ObjectMapper objectMapper = MavenXmlMapper.writeMapper();
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
            PrettyPrinter pp = new DefaultXmlPrettyPrinter().withCustomNewLine(useCRLFNewLines ? "\r\n" : "\n");
            return objectMapper.writer(pp)
                    .writeValueAsString(new GradleEnterpriseConfiguration(serverConfiguration, buildScanConfiguration));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private BuildScanConfiguration buildScanConfiguration() {
        if (uploadInBackground != null || publishCriteria != null || captureGoalInputFiles != null) {
            return new BuildScanConfiguration(uploadInBackground,
                    publishCriteria != null ? publishCriteria.xmlName : null,
                    captureGoalInputFiles != null ? new Capture(captureGoalInputFiles) : null);
        }
        return null;
    }

    private static Xml.Document createNewXml(String filePath, @Language("xml") String fileContents) {
        XmlParser parser = new XmlParser();
        //noinspection OptionalGetWithoutIsPresent
        Xml.Document brandNewFile = parser.parse(fileContents).findAny().get();
        return brandNewFile.withSourcePath(Paths.get(filePath));
    }

    /**
     * Return true if the `.mvn/extensions.xml` already includes `gradle-enterprise-maven-extension`
     */
    private boolean findExistingEnterpriseExtension(Xml.Document extensionsXml) {
        XPathMatcher xPathMatcher = new XPathMatcher("/extensions/extension/artifactId");
        return new XmlIsoVisitor<AtomicBoolean>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, AtomicBoolean found) {
                if (found.get()) {
                    return tag;
                }
                tag = super.visitTag(tag, found);
                if (xPathMatcher.matches(getCursor())) {
                    Optional<String> maybeArtifactId = tag.getValue();
                    if (maybeArtifactId.isPresent() && maybeArtifactId.get().equals(GRADLE_ENTERPRISE_MAVEN_EXTENSION_ARTIFACT_ID)) {
                        found.set(true);
                    }
                }
                return tag;
            }
        }.reduce(extensionsXml, new AtomicBoolean()).get();
    }

    /**
     * Add `gradle-enterprise-maven-extension` to the file `.mvn/extensions.xml`,
     * this method assumes that `gradle-enterprise-maven-extension` does not exist yet, and it should have been checked.
     */
    private Xml.Document addEnterpriseExtension(Xml.Document extensionsXml, ExecutionContext ctx) {
        @Language("xml")
        String tagSource = version != null ? String.format(ENTERPRISE_TAG_FORMAT, version) : ENTERPRISE_TAG_FORMAT_WITHOUT_VERSION;
        AddToTagVisitor<ExecutionContext> addToTagVisitor = new AddToTagVisitor<>(
                extensionsXml.getRoot(),
                Xml.Tag.build(tagSource));
        return (Xml.Document) addToTagVisitor.visitNonNull(extensionsXml, ctx);
    }
}
