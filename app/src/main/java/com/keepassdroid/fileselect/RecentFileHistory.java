/*
 * Copyright 2013-2016 Brian Pellin.
 *     
 * This file is part of KeePassDroid.
 *
 *  KeePassDroid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDroid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepassdroid.fileselect;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.android.keepass.R;
import com.keepassdroid.compat.EditorCompat;
import com.keepassdroid.utils.UriUtil;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.TextUtils;

public class RecentFileHistory {

    private static String DB_KEY = "recent_databases";
    private static String KEYFILE_KEY = "recent_keyfiles";
    private static String PWD_KEY = "recent_pwd";

    private List<String> databases = new ArrayList<String>();
    private List<String> keyfiles = new ArrayList<String>();
    private List<String> passwords = new ArrayList<String>();
    private Context ctx;
    private SharedPreferences prefs;
    private OnSharedPreferenceChangeListener listner;
    private boolean enabled;
    private boolean init = false;

    public RecentFileHistory(Context c) {
        ctx = c.getApplicationContext();

        prefs = PreferenceManager.getDefaultSharedPreferences(c);
        enabled = prefs.getBoolean(ctx.getString(R.string.recentfile_key), ctx.getResources().getBoolean(R.bool.recentfile_default));
        listner = new OnSharedPreferenceChangeListener() {

            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                    String key) {
                if (key.equals(ctx.getString(R.string.recentfile_key))) {
                    enabled = sharedPreferences.getBoolean(ctx.getString(R.string.recentfile_key), ctx.getResources().getBoolean(R.bool.recentfile_default));
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listner);
    }

    private synchronized void init() {
        if (!init) {
            if (!upgradeFromSQL()) {
                loadPrefs();
            }

            init = true;
        }
    }

    private boolean upgradeFromSQL() {

        try {
            // Check for a database to upgrade from
            if (!sqlDatabaseExists()) {
                return false;
            }

            databases.clear();
            keyfiles.clear();
            passwords.clear();

            FileDbHelper helper = new FileDbHelper(ctx);
            helper.open();
            Cursor cursor = helper.fetchAllFiles();

            int dbIndex = cursor.getColumnIndex(FileDbHelper.KEY_FILE_FILENAME);
            int keyIndex = cursor.getColumnIndex(FileDbHelper.KEY_FILE_KEYFILE);
            int passwordIndex = cursor.getColumnIndex(FileDbHelper.KEY_FILE_PASSWORD);

            if(cursor.moveToFirst()) {
                while (cursor.moveToNext()) {
                    String filename = cursor.getString(dbIndex);
                    String keyfile = cursor.getString(keyIndex);
                    String password = cursor.getString(passwordIndex);

                    databases.add(filename);
                    keyfiles.add(keyfile);
                    passwords.add(password);
                }
            }

            savePrefs();

            cursor.close();
            helper.close();

        } catch (Exception e) {
            // If upgrading fails, we'll just give up on it.
        }

        try {
            FileDbHelper.deleteDatabase(ctx);
        } catch (Exception e) {
            // If we fail to delete it, just move on
        }

        return true;
    }

    private boolean sqlDatabaseExists() {
        File db = ctx.getDatabasePath(FileDbHelper.DATABASE_NAME);
        return db.exists();
    }

    public void createFile(Uri uri, Uri keyUri,String password) {
        if (!enabled || uri == null) return;

        init();

        // Remove any existing instance of the same filename
        deleteFile(uri, false);

        databases.add(0, uri.toString());

        String key = (keyUri == null) ? "" : keyUri.toString();
        keyfiles.add(0, key);

        if(password==null) {
            password="";
        }
        passwords.add(0, password);

        trimLists();
        savePrefs();
    }

    public boolean hasRecentFiles() {
        if (!enabled) return false;

        init();

        return databases.size() > 0;
    }

    public String getDatabaseAt(int i) {
        init();
        return databases.get(i);
    }

    public String getKeyfileAt(int i) {
        init();
        return keyfiles.get(i);
    }

    public String getPasswordAt(int i) {
        init();
        return passwords.get(i);
    }

    private void loadPrefs() {
        loadList(databases, DB_KEY);
        loadList(keyfiles, KEYFILE_KEY);
        loadList(passwords, PWD_KEY);
    }

    private void savePrefs() {
        saveList(DB_KEY, databases);
        saveList(KEYFILE_KEY, keyfiles);
        saveList(PWD_KEY, passwords);
    }

    private void loadList(List<String> list, String keyprefix) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        int size = prefs.getInt(keyprefix, 0);

        list.clear();
        for (int i = 0; i < size; i++) {
            list.add(prefs.getString(keyprefix + "_" + i, ""));
        }
    }

    private void saveList(String keyprefix, List<String> list) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        SharedPreferences.Editor edit = prefs.edit();
        int size = list.size();
        edit.putInt(keyprefix, size);

        for (int i = 0; i < size; i++) {
            edit.putString(keyprefix + "_" + i, list.get(i));
        }
        EditorCompat.apply(edit);
    }

    public void deleteFile(Uri uri) {
        deleteFile(uri, true);
    }

    public void deleteFile(Uri uri, boolean save) {
        init();

        String uriName = uri.toString();
        String fileName = uri.getPath();

        for (int i = 0; i < databases.size(); i++) {
            String entry = databases.get(i);
            if (uriName.equals(entry) || fileName.equals(entry)) {
                databases.remove(i);
                keyfiles.remove(i);
                passwords.remove(i);
                break;
            }
        }

        if (save) {
            savePrefs();
        }
    }

    public List<String> getDbList() {
        init();

        return databases;
    }

    public Uri getKeyFileByName(Uri database) {
        if (!enabled) return null;

        init();

        int size = databases.size();
        for (int i = 0; i < size; i++) {
            if (UriUtil.equalsDefaultfile(database,databases.get(i))) {
                return UriUtil.parseDefaultFile(keyfiles.get(i));
            }
        }

        return null;
    }
    public String getPasswordByName(Uri database) {
        if (!enabled) return null;
        init();

        int size = databases.size();
        for (int i = 0; i < size; i++) {
            if (UriUtil.equalsDefaultfile(database,databases.get(i))) {
                return passwords.get(i);
            }
        }

        return null;
    }



    public void deleteAll() {
        init();

        databases.clear();
        keyfiles.clear();
        passwords.clear();

        savePrefs();
    }

    public void deleteAllKeys() {
        init();

        keyfiles.clear();
        passwords.clear();
        int size = databases.size();
        for (int i = 0; i < size; i++) {
            keyfiles.add("");
            passwords.add("");
        }

        savePrefs();
    }

    private void trimLists() {
        int size = databases.size();
        for (int i = FileDbHelper.MAX_FILES; i < size; i++) {
            databases.remove(i);
            keyfiles.remove(i);
            passwords.remove(i);
        }
    }
}
