/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chromium.latency.walt;


import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceFragmentCompat;
import android.support.v7.preference.PreferenceScreen;
import android.support.v7.widget.Toolbar;
import android.view.View;


public class SettingsFragment extends PreferenceFragmentCompat implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    private Toolbar toolbar;

    public SettingsFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.preferences, rootKey);

        PreferenceScreen prefMidiScreen =
                (PreferenceScreen) getPreferenceScreen().findPreference("pref_midi_screen");
        if (prefMidiScreen != null) {
            boolean hasMidi =
                    getContext().getPackageManager().hasSystemFeature("android.software.midi");
            prefMidiScreen.setVisible(hasMidi);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        toolbar = (Toolbar) getActivity().findViewById(R.id.toolbar_main);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.ColorBackground));
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof NumberPickerPreference) {
            DialogFragment fragment = NumberPickerPreference.
                    NumberPickerPreferenceDialogFragmentCompat.newInstance(preference.getKey());
            fragment.setTargetFragment(this, 0);
            fragment.show(getFragmentManager(),
                    "android.support.v7.preference.PreferenceFragment.DIALOG");
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    @Override
    public Fragment getCallbackFragment() {
        return this;
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat preferenceFragmentCompat,
                                           PreferenceScreen preferenceScreen) {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, preferenceScreen.getKey());
        fragment.setArguments(args);

        FragmentTransaction ft = preferenceFragmentCompat.getFragmentManager().beginTransaction();
        ft.add(R.id.fragment_container, fragment, preferenceScreen.getKey());
        ft.addToBackStack(preferenceScreen.getTitle().toString());
        ft.commit();

        toolbar.setTitle(preferenceScreen.getTitle());
        return true;
    }

}
