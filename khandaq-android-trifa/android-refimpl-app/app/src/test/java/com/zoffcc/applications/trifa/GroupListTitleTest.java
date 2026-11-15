package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * KHANDAQ (0.2.56) — the name a group is offered under when something is shared into Khandaq.
 *
 * <p>QA live 2026-11-15: the chat picker of the share sheet listed the groups joined by their ID under
 * the first characters of the ID, while the chat list showed their names. The picker read only the
 * stored name, which such a group did not have until the app was started again; the chat list asks
 * toxcore first ({@link HelperGroup#get_effective_group_title}), and the picker now does the same.
 */
public class GroupListTitleTest
{
    private static final String GROUP_ID = "64077ffa76cede3b392535c691a360fbe7ce4d0d4b8dcd968226ef988c0ae4ec";

    @Test
    public void aGroupJoinedByItsIdIsOfferedUnderTheNameToxcoreHas()
    {
        // Joined by its ID: toxcore has the name, the stored one is still empty.
        assertEquals("QA56 G4 iOS-queue", HelperGroup.group_list_title("QA56 G4 iOS-queue", "", GROUP_ID));
        assertEquals("QA56 G4 iOS-queue", HelperGroup.group_list_title("QA56 G4 iOS-queue", null, GROUP_ID));
    }

    @Test
    public void toxcoresTitleComesBeforeTheStoredOne()
    {
        // As the chat list shows it.
        assertEquals("New name", HelperGroup.group_list_title("New name", "Old name", GROUP_ID));
    }

    @Test
    public void theStoredNameStandsWhileToxcoreHasNone()
    {
        assertEquals("QA56 G3 B-founder", HelperGroup.group_list_title("", "QA56 G3 B-founder", GROUP_ID));
        assertEquals("QA56 G3 B-founder", HelperGroup.group_list_title("  ", "QA56 G3 B-founder", GROUP_ID));
        assertEquals("QA56 G3 B-founder", HelperGroup.group_list_title(null, "QA56 G3 B-founder", GROUP_ID));
    }

    @Test
    public void theStartOfTheIdIsTheLastResort()
    {
        assertEquals("64077ffa", HelperGroup.group_list_title(null, null, GROUP_ID));
        assertEquals("64077ffa", HelperGroup.group_list_title("", " ", GROUP_ID));
        assertEquals("abc", HelperGroup.group_list_title(null, null, "abc"));
        assertEquals("", HelperGroup.group_list_title(null, null, null));
    }
}
