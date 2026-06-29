package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ProfileTabFragment extends Fragment
{
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        return inflater.inflate(R.layout.fragment_profile_tab, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

        if (getChildFragmentManager().findFragmentById(R.id.profile_tab_container) == null)
        {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.profile_tab_container, new ProfileContentFragment())
                    .commit();
        }
    }

    // KHANDAQ: reload the profile (name/status value rows etc.) each time the tab is shown — the
    // nested fragment's onResume doesn't re-fire on the parent's show/hide.
    @Override
    public void onHiddenChanged(boolean hidden)
    {
        super.onHiddenChanged(hidden);
        if (!hidden)
        {
            final Fragment f = getChildFragmentManager().findFragmentById(R.id.profile_tab_container);
            if (f instanceof ProfileContentFragment)
            {
                ((ProfileContentFragment) f).reloadProfileFromTox();
            }
        }
    }
}
