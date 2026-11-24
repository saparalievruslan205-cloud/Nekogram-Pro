package org.telegram.messenger;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards against Resources$NotFoundException caused by calling Android Context/Resources
 * getString/getText (or referencing in runtime Android XMLs) on strings discarded from
 * resources.arsc by TelegramStringsTask.
 */
public class DiscardedStringResourceUsageTest {

    private static final Pattern EXCLUSIONS_BLOCK = Pattern.compile(
            "GENERATED_EXCLUSIONS\\s*=\\s*setOf\\(([^)]*)\\)",
            Pattern.DOTALL
    );
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern ANDROID_GET_STRING = Pattern.compile(
            "\\b(context|ctx|activity|act|resources|res|getResources\\(\\)|getContext\\(\\)|getParentActivity\\(\\)|getApplicationContext\\(\\)|applicationContext|this)"
                    + "\\.(getString|getText)\\(\\s*(?:[\\w.]+\\.)?R\\.string\\.([A-Za-z0-9_]+)"
    );
    private static final Pattern XML_STRING_REF = Pattern.compile(
            "android:[A-Za-z0-9_]+\\s*=\\s*\"@string/([A-Za-z0-9_]+)\""
    );

    private static File repoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "settings.gradle").isFile()
                    && new File(dir, "TMessagesProj_App").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("Could not locate repository root from " + System.getProperty("user.dir"));
    }

    private static Set<String> readExcludedStrings(File root) throws Exception {
        File taskFile = new File(root,
                "build-logic/convention/src/main/kotlin/org/telegram/tasks/TelegramStringsTask.kt");
        String content = new String(Files.readAllBytes(taskFile.toPath()), StandardCharsets.UTF_8);
        Matcher block = EXCLUSIONS_BLOCK.matcher(content);
        assertTrue("Expected GENERATED_EXCLUSIONS in TelegramStringsTask.kt", block.find());
        Set<String> excluded = new HashSet<>();
        Matcher name = QUOTED_NAME.matcher(block.group(1));
        while (name.find()) {
            excluded.add(name.group(1));
        }
        assertFalse("GENERATED_EXCLUSIONS must not be empty", excluded.isEmpty());
        return excluded;
    }

    private static Map<String, String> parseStringsXml(File file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList nodes = doc.getElementsByTagName("string");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            result.put(el.getAttribute("name"), el.getTextContent());
        }
        return result;
    }

    @Test
    public void proUpdateStringsExistAndFormatCleanlyInEnglishAndRussian() throws Exception {
        File root = repoRoot();
        Map<String, String> en = parseStringsXml(new File(root, "TMessagesProj_App/src/main/res/values/strings_pro.xml"));
        Map<String, String> ru = parseStringsXml(new File(root, "TMessagesProj_App/src/main/res/values-ru/strings_pro.xml"));

        assertEquals("English and Russian strings_pro.xml must define the same keys", en.keySet(), ru.keySet());
        assertTrue(en.containsKey("ProUpdateAvailable"));
        assertTrue(en.containsKey("ProUpdateDownload"));
        assertTrue(en.containsKey("ProUpdateLater"));
        assertTrue(en.containsKey("ProUpdateDownloading"));
        assertTrue(en.containsKey("ProUpdateProgress"));
        assertTrue(en.containsKey("ProUpdateProgressUnknown"));
        assertTrue(en.containsKey("ProUpdateDownloadFailed"));
        assertTrue(en.containsKey("ProUpdateCancel"));
        assertTrue(en.containsKey("ProUpdateOK"));

        for (Map<String, String> strings : new Map[]{en, ru}) {
            String avail = String.format(Locale.US, strings.get("ProUpdateAvailable"), "13.2.2");
            assertTrue(avail.contains("13.2.2"));
            String progress = String.format(Locale.US, strings.get("ProUpdateProgress"), "12 MB", "85 MB", 14);
            assertTrue(progress.contains("12 MB") && progress.contains("85 MB") && progress.contains("14%"));
            String unknown = String.format(Locale.US, strings.get("ProUpdateProgressUnknown"), "12 MB");
            assertTrue(unknown.contains("12 MB"));
        }
    }

    @Test
    public void noAppCodeCallsAndroidContextGetStringOnDiscardedStrings() throws Exception {
        File root = repoRoot();
        Set<String> excluded = readExcludedStrings(root);
        List<String> violations = new ArrayList<>();

        File[] sourceRoots = new File[]{
                new File(root, "TMessagesProj_App/src/main/java"),
                new File(root, "TMessagesProj/src/main/java")
        };

        for (File sourceRoot : sourceRoots) {
            try (Stream<Path> files = Files.walk(sourceRoot.toPath())) {
                List<Path> list = new ArrayList<>();
                files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".java") || name.endsWith(".kt");
                }).forEach(list::add);

                for (Path file : list) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (line.contains("android.R.string.")) {
                            continue;
                        }
                        Matcher m = ANDROID_GET_STRING.matcher(line);
                        while (m.find()) {
                            String stringName = m.group(3);
                            if (!excluded.contains(stringName)) {
                                violations.add(root.toPath().relativize(file) + ":" + (i + 1)
                                        + " uses " + m.group(1) + "." + m.group(2)
                                        + "(R.string." + stringName + ")");
                            }
                        }
                    }
                }
            }
        }

        assertTrue("Discarded strings must be loaded via LocaleController, not Context.getString:\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void runtimeXmlStringReferencesAreNotDiscarded() throws Exception {
        File root = repoRoot();
        Set<String> excluded = readExcludedStrings(root);
        List<String> violations = new ArrayList<>();

        File[] xmlTargets = new File[]{
                new File(root, "TMessagesProj/src/main/AndroidManifest.xml"),
                new File(root, "TMessagesProj_App/src/main/AndroidManifest.xml"),
                new File(root, "TMessagesProj/src/main/res/xml")
        };

        for (File target : xmlTargets) {
            if (!target.exists()) {
                continue;
            }
            List<Path> xmlFiles = new ArrayList<>();
            if (target.isFile()) {
                xmlFiles.add(target.toPath());
            } else {
                try (Stream<Path> files = Files.walk(target.toPath())) {
                    files.filter(p -> p.getFileName().toString().endsWith(".xml")).forEach(xmlFiles::add);
                }
            }
            for (Path file : xmlFiles) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = XML_STRING_REF.matcher(lines.get(i));
                    while (m.find()) {
                        String stringName = m.group(1);
                        if (!excluded.contains(stringName)) {
                            violations.add(root.toPath().relativize(file) + ":" + (i + 1)
                                    + " references @string/" + stringName + " which is discarded by TelegramStringsTask");
                        }
                    }
                }
            }
        }

        assertTrue("Runtime Android XML files reference discarded strings:\n"
                + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void stableIdsExclusionsMatchGeneratedExclusions() throws Exception {
        File root = repoRoot();
        File taskFile = new File(root,
                "build-logic/convention/src/main/kotlin/org/telegram/tasks/TelegramStringsTask.kt");
        String content = new String(Files.readAllBytes(taskFile.toPath()), StandardCharsets.UTF_8);
        Set<String> generated = readExcludedStrings(root);

        if (content.contains("STABLE_IDS_EXCLUSIONS = GENERATED_EXCLUSIONS")) {
            return;
        }
        Matcher block = Pattern.compile(
                "STABLE_IDS_EXCLUSIONS\\s*=\\s*setOf\\(([^)]*)\\)",
                Pattern.DOTALL
        ).matcher(content);
        assertTrue("Expected STABLE_IDS_EXCLUSIONS in TelegramStringsTask.kt", block.find());
        Set<String> stable = new HashSet<>();
        Matcher name = QUOTED_NAME.matcher(block.group(1));
        while (name.find()) {
            stable.add(name.group(1));
        }
        assertEquals(
                "Every string kept in resources.arsc (GENERATED_EXCLUSIONS) must also be in STABLE_IDS_EXCLUSIONS to avoid sparse ResTable_type bloat",
                generated,
                stable
        );
    }
}
