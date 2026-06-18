package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class SettingsTabFragment extends Fragment
{
    private static final int ITEM_GENERAL = 0;
    private static final int ITEM_NOTIFICATIONS = 1;
    private static final int ITEM_MAINTENANCE = 2;
    private static final int ITEM_ABOUT = 3;

    private ListView listView;
    private View prefsContainer;
    private View backupFooter;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        return inflater.inflate(R.layout.fragment_settings_tab, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

        listView = view.findViewById(R.id.settings_tab_list);
        prefsContainer = view.findViewById(R.id.settings_tab_prefs_container);

        backupFooter = LayoutInflater.from(requireContext()).inflate(R.layout.settings_backup_footer, listView, false);
        listView.addFooterView(backupFooter, null, false);

        final String[] labels = new String[] {
                getString(R.string.pref_header_general),
                getString(R.string.pref_header_notifications),
                getString(R.string.MainActivity_maint),
                getString(R.string.MainActivity_about),
        };

        listView.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, labels));
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View itemView, int position, long id)
            {
                final MainActivity activity = (MainActivity) getActivity();
                if (activity == null || Callstate.state != 0)
                {
                    return;
                }

                switch (position)
                {
                    case ITEM_GENERAL:
                        showPreferenceFragment(new SettingsActivity.GeneralPreferenceFragment());
                        break;
                    case ITEM_NOTIFICATIONS:
                        showPreferenceFragment(new SettingsActivity.NotificationPreferenceFragment());
                        break;
                    case ITEM_MAINTENANCE:
                        activity.startActivity(new Intent(activity, MaintenanceActivity.class));
                        break;
                    case ITEM_ABOUT:
                        activity.startActivity(new Intent(activity, Aboutpage.class));
                        break;
                }
            }
        });

        backupFooter.findViewById(R.id.settings_backup_export_card).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                final MainActivity activity = (MainActivity) getActivity();
                if (activity == null || Callstate.state != 0)
                {
                    return;
                }
                final Intent exportIntent = new Intent(activity, MaintenanceActivity.class);
                exportIntent.putExtra(ToxProfileImportHelper.EXTRA_OPEN_EXPORT_PICKER, true);
                activity.startActivity(exportIntent);
            }
        });

        backupFooter.findViewById(R.id.settings_backup_import_card).setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                final MainActivity activity = (MainActivity) getActivity();
                if (activity == null || Callstate.state != 0)
                {
                    return;
                }
                final Intent importIntent = new Intent(activity, MaintenanceActivity.class);
                importIntent.putExtra(ToxProfileImportHelper.EXTRA_OPEN_IMPORT_PICKER, true);
                activity.startActivity(importIntent);
            }
        });
    }

    public boolean handleBackPress()
    {
        if (prefsContainer == null || listView == null)
        {
            return false;
        }

        if (prefsContainer.getVisibility() != View.VISIBLE)
        {
            return false;
        }

        final android.app.FragmentManager fragmentManager = requireActivity().getFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0)
        {
            fragmentManager.popBackStackImmediate();
            if (fragmentManager.getBackStackEntryCount() == 0)
            {
                showHeaderList();
            }
            return true;
        }

        showHeaderList();
        return true;
    }

    private void showPreferenceFragment(final android.app.Fragment fragment)
    {
        if (!isAdded())
        {
            return;
        }

        listView.setVisibility(View.GONE);
        prefsContainer.setVisibility(View.VISIBLE);

        try
        {
            requireActivity().getFragmentManager().beginTransaction()
                    .replace(R.id.settings_tab_prefs_container, fragment)
                    .addToBackStack("settings_tab_pref")
                    .commitAllowingStateLoss();
        }
        catch (Exception e)
        {
            showHeaderList();
        }
    }

    private void showHeaderList()
    {
        listView.setVisibility(View.VISIBLE);
        prefsContainer.setVisibility(View.GONE);
    }
}
