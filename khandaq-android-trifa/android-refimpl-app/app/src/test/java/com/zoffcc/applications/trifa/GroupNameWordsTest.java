package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #13, 27.11): a group's topic is the name it shows, so its settings and its dossier call it the group's
 * name in the four languages and never say "topic"; Khandaq Community keeps its topic words as they were. The words
 * are pinned in the resources, the screens on their sources with the comments left out.
 */
public class GroupNameWordsTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";
    /** The folder of each language kept, with how it says "topic". */
    private static final String[][] LANGUAGES = {{"values", "(?i)topic"}, {"values-ru", "(?iu)(^|[^\\p{L}])тем"},
                                                 {"values-ar", "موضوع"}, {"values-zh-rCN", "主题"}};
    /** The words of a group's topic, in strings_s5_f3.xml. */
    private static final String[] GROUP_NAME_STRINGS = {"air_gs_group_name", "air_gs_group_name_note",
                                                        "air_gs_group_name_locked", "air_gs_group_name_not_observers",
                                                        "air_gs_group_name_empty", "air_gs_group_name_lock"};
    /** The words of Khandaq Community's topic, in strings_s5_f3.xml: they still say it. */
    private static final String[] COMMUNITY_TOPIC_STRINGS = {"air_gs_topic", "air_gs_topic_none", "air_gs_topic_note",
                                                             "air_gs_topic_locked", "air_gs_topic_not_observers",
                                                             "air_gs_topic_empty", "air_gs_topic_lock"};

    @Test
    public void a_group_s_words_say_its_name_in_the_four_languages_and_never_topic()
    {
        for (final String[] language : LANGUAGES)
        {
            final Pattern topic = Pattern.compile(language[1]);
            final String settings = read(RES + language[0] + "/strings_s5_f3.xml");
            final String air = read(RES + language[0] + "/strings_air.xml");
            for (final String name : GROUP_NAME_STRINGS)
            {
                saysNoTopic(language[0], name, string(settings, name, language[0]), topic);
            }
            saysNoTopic(language[0], "air_group_settings_detail_name", string(air, "air_group_settings_detail_name",
                                                                              language[0]), topic);
            // A new group is never Khandaq Community: its note has one name, which can change later.
            saysNoTopic(language[0], "air_new_group_name_note", string(air, "air_new_group_name_note", language[0]),
                        topic);
        }
    }

    @Test
    public void a_group_s_name_reads_as_the_rest_of_each_language()
    {
        final String[][] expected = {
                {"values", "Group name", "The name cannot be empty.",
                 "Your name in the group, the group\\'s name, who joins and who writes",
                 "Everyone you invite sees it. You can change it later in the group\\'s settings."},
                {"values-ru", "Название группы", "Название не может быть пустым.",
                 "Ваше имя в группе, название, кто входит и кто пишет",
                 "Его увидят все приглашённые. Потом его можно поменять в настройках группы."},
                {"values-ar", "اسم المجموعة", "لا يمكن أن يكون الاسم فارغًا.",
                 "اسمك في المجموعة، اسم المجموعة، من ينضم ومن يكتب",
                 "سيراه كل من تدعوهم. يمكنك تغييره لاحقًا من إعدادات المجموعة."},
                {"values-zh-rCN", "群组名称", "名称不能为空。", "你在群组中的名字、群组名称、谁能加入、谁能发言",
                 "你邀请的每个人都会看到它。之后可以在群组设置中更改。"}};
        for (final String[] language : expected)
        {
            final String settings = read(RES + language[0] + "/strings_s5_f3.xml");
            final String air = read(RES + language[0] + "/strings_air.xml");
            assertEquals(language[0], language[1], string(settings, "air_gs_group_name", language[0]));
            assertEquals(language[0], language[2], string(settings, "air_gs_group_name_empty", language[0]));
            assertEquals(language[0], language[3], string(air, "air_group_settings_detail_name", language[0]));
            assertEquals(language[0], language[4], string(air, "air_new_group_name_note", language[0]));
        }
    }

    @Test
    public void khandaq_community_keeps_its_topic_words()
    {
        for (final String[] language : LANGUAGES)
        {
            final Pattern topic = Pattern.compile(language[1]);
            final String settings = read(RES + language[0] + "/strings_s5_f3.xml");
            for (final String name : COMMUNITY_TOPIC_STRINGS)
            {
                final String value = string(settings, name, language[0]);
                assertTrue(language[0] + " " + name + ": " + value, topic.matcher(value).find());
            }
            final String detail = string(read(RES + language[0] + "/strings_air.xml"), "air_group_settings_detail",
                                         language[0]);
            assertTrue(language[0] + " air_group_settings_detail: " + detail, topic.matcher(detail).find());
        }
        assertEquals("Topic", string(read(RES + "values/strings_s5_f3.xml"), "air_gs_topic", "values"));
        assertEquals("No topic", string(read(RES + "values/strings_s5_f3.xml"), "air_gs_topic_none", "values"));
    }

    @Test
    public void the_settings_take_every_word_of_the_topic_from_the_group_s_words()
    {
        final String screen = bare(code(JAVA + "GroupInfoActivity.java"));
        assertTrue(screen.contains("groupId=id;words=AirGroupSettingsModel.topicWords(id);"));
        // The row: the group's title (its topic, else the name it was created with), never "No topic" for a group.
        assertTrue(screen.contains("finalStringtopicTitle=getString(words.title);"));
        assertTrue(screen.contains("AirGroupSettingsModel.topicRowValue(words,s.topic,s.name,groupId,"
                                   + "getString(R.string.air_gs_topic_none));"));
        assertTrue(screen.contains("AirGroupSettingsModel.topicNote(words,!s.live||topicOpens,s.topicLock);"));
        assertTrue(screen.contains("groupName=ask(\"groupname\",()->MainActivity.tox_group_get_name(num));"));
        assertTrue(screen.contains("s.name=isKnown(groupName)?groupName:\"\";"));
        assertTrue(screen.contains("&&name.equals(other.name)"));
        // The founder's lock, the page, its field, its hint and its refusal.
        assertTrue(screen.contains("newAirSettingRow(this,getString(words.lock),false);"));
        assertTrue(screen.contains("setBarTitle(fieldTitle(f));"));
        assertTrue(screen.contains("newAirStepKit.LineField(this,getString(fieldTitle(f)),f==Field.PASSWORD);"));
        assertTrue(screen.contains("returnf==Field.TOPIC?words.title:f.title;"));
        assertEquals("the title of a field is read in fieldTitle only", 1, count(screen, "f.title"));
        assertTrue(screen.contains("returnAirGroupSettingsModel.topicFieldValue(words,s.topic,s.name);"));
        assertTrue(screen.contains("caseTOPIC:returngetString(words.note);"));
        assertTrue(screen.contains("refuse(getString(words.empty));"));
        // Save still sets the topic.
        assertTrue(screen.contains("HelperGroup.save_group_title_if_changed(groupId,topic)"));
        for (final String direct : new String[]{"R.string.air_gs_topic_note", "R.string.air_gs_topic_locked",
                                                "R.string.air_gs_topic_not_observers", "R.string.air_gs_topic_empty",
                                                "R.string.air_gs_topic_lock"})
        {
            assertFalse(direct, screen.contains(direct));
        }
        // The topic's title of the enum is Khandaq Community's, and only fieldTitle reads it.
        assertEquals(1, count(screen, "R.string.air_gs_topic,"));
    }

    @Test
    public void the_dossier_names_the_settings_as_they_are()
    {
        final String dossier = bare(code(JAVA + "AirGroupDossierActivity.java"));
        assertTrue(dossier.contains(".detail(getString(AirGroupSettingsModel.topicWords(groupId).settingsDetail));"));
        assertFalse(dossier.contains("R.string.air_group_settings_detail"));
    }

    private static void saysNoTopic(final String language, final String name, final String value, final Pattern topic)
    {
        assertFalse(language + " " + name + ": " + value, topic.matcher(value).find());
        assertFalse(language + " " + name + " is empty", value.trim().isEmpty());
    }

    /** The value of the string {@code name} in {@code strings}, once. */
    private static String string(final String strings, final String name, final String language)
    {
        final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(strings);
        assertTrue(language + " " + name, found.find());
        final String value = found.group(1);
        assertFalse(language + " " + name + " is there once", found.find());
        return value;
    }

    private static String bare(final String text)
    {
        return text.replaceAll("\\s+", "");
    }

    private static int count(final String text, final String part)
    {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            n++;
        }
        return n;
    }

    private static String code(final String name)
    {
        return AntispamOnRequestTest.code(read(name));
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name + ": " + e.getMessage());
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
