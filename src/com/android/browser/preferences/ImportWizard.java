/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.browser.preferences;

import com.android.browser.BrowserBookmarksPage;
import com.android.browser.R;
import com.android.browser.view.EventRedirectingFrameLayout;

import android.accounts.Account;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.DialogInterface.OnKeyListener;
import android.content.DialogInterface.OnShowListener;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.BrowserContract;
import android.provider.BrowserContract.Bookmarks;
import android.provider.BrowserContract.ChromeSyncColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;

public class ImportWizard extends DialogFragment implements OnClickListener,
        OnItemClickListener {

    static final String TAG = "BookmarkImportWizard";

    static final int PAGE_IMPORT_OR_DELETE = 0;
    static final int PAGE_SELECT_ACCOUNT = 1;
    static final int PAGE_CONFIRMATION = 2;

    static final String STATE_CURRENT_PAGE = "wizard.current_page";
    static final String STATE_IMPORT_OR_DELETE = "wizard.import_or_delete";
    static final String STATE_SELECTED_ACCOUNT = "wizard.selected_account";

    static final String ARG_ACCOUNTS = "accounts";

    AlertDialog mDialog;
    EventRedirectingFrameLayout mPages;
    int mCurrentPage;
    Button mPositiveButton, mNegativeButton;
    ListView mImportOrDelete, mSelectAccount;
    Account[] mAccounts;
    TextView mSelectAccountDescription, mConfirmation;

    static ImportWizard newInstance(Account[] accounts) {
        ImportWizard wizard = new ImportWizard();
        Bundle args = new Bundle();
        args.putParcelableArray(ARG_ACCOUNTS, accounts);
        wizard.setArguments(args);
        return wizard;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAccounts = (Account[]) getArguments().getParcelableArray(ARG_ACCOUNTS);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        mDialog = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.import_bookmarks_dialog_title)
                .setView(createView(savedInstanceState))
                .setPositiveButton("?", null) // This is just a placeholder
                .setNegativeButton("?", null) // Ditto
                .setOnKeyListener(new OnKeyListener() {
                    @Override
                    public boolean onKey(DialogInterface arg0, int arg1, KeyEvent key) {
                        if (key.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                            if (key.getAction() == KeyEvent.ACTION_UP
                                    && !key.isCanceled()) {
                                mNegativeButton.performClick();
                            }
                            return true;
                        }
                        return false;
                    }
                })
                .create();
        mDialog.setOnShowListener(new OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                mPositiveButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                mNegativeButton = mDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                mPositiveButton.setOnClickListener(ImportWizard.this);
                mNegativeButton.setOnClickListener(ImportWizard.this);
                setupAnimations();
                updateNavigation();
            }
        });
        return mDialog;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_PAGE, mCurrentPage);
        outState.putInt(STATE_IMPORT_OR_DELETE, mImportOrDelete.getCheckedItemPosition());
        outState.putInt(STATE_SELECTED_ACCOUNT, mSelectAccount.getCheckedItemPosition());
    }

    public View createView(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View root = inflater.inflate(R.layout.bookmark_sync_wizard, null);
        mPages = (EventRedirectingFrameLayout) root.findViewById(R.id.pages);
        if (mPages.getChildCount() < 1) {
            throw new IllegalStateException("no pages in wizard!");
        }
        if (savedInstanceState != null) {
            mCurrentPage = savedInstanceState.getInt(STATE_CURRENT_PAGE);
        } else {
            mCurrentPage = 0;
        }
        setupPage1(savedInstanceState);
        setupPage2(savedInstanceState);
        setupPage3(savedInstanceState);
        for (int i = 0; i < mPages.getChildCount(); i++) {
            View v = mPages.getChildAt(i);
            if (i <= mCurrentPage) {
                preparePage();
                v.setVisibility(View.VISIBLE);
            } else {
                v.setVisibility(View.GONE);
            }
        }
        mPages.setTargetChild(mCurrentPage);
        return root;
    }

    void setupPage1(Bundle savedInstanceState) {
        mImportOrDelete = (ListView) mPages.findViewById(R.id.add_remove_bookmarks);
        // Add an empty header so we get a divider above the list
        mImportOrDelete.addHeaderView(new View(getActivity()));
        Resources res = getActivity().getResources();
        String[] choices = new String[] {
                res.getString(R.string.import_bookmarks_dialog_add),
                res.getString(R.string.import_bookmarks_dialog_remove)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(),
                R.layout.bookmark_sync_wizard_item, choices);
        mImportOrDelete.setAdapter(adapter);
        if (savedInstanceState != null) {
            int position = savedInstanceState.getInt(STATE_IMPORT_OR_DELETE);
            if (position == ListView.INVALID_POSITION) {
                mImportOrDelete.clearChoices();
            } else {
                mImportOrDelete.setItemChecked(position, true);
            }
        }
        mImportOrDelete.setOnItemClickListener(this);
    }

    void setupPage2(Bundle savedInstanceState) {
        mSelectAccount = (ListView) mPages.findViewById(R.id.select_account);
        mSelectAccountDescription =
                (TextView) mPages.findViewById(R.id.select_account_description);
        // Add an empty header so we get a divider above the list
        mSelectAccount.addHeaderView(new View(getActivity()));
        Resources res = getActivity().getResources();
        String[] accountNames = new String[mAccounts.length];
        for (int i = 0; i < mAccounts.length; i++) {
            accountNames[i] = mAccounts[i].name;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(),
                R.layout.bookmark_sync_wizard_item, accountNames);
        mSelectAccount.setAdapter(adapter);
        mSelectAccount.setItemChecked(mSelectAccount.getHeaderViewsCount(), true);
        if (savedInstanceState != null) {
            int position = savedInstanceState.getInt(STATE_SELECTED_ACCOUNT);
            if (position != ListView.INVALID_POSITION) {
                mSelectAccount.setItemChecked(position, true);
            }
        }
        mSelectAccount.setOnItemClickListener(this);
    }

    void setupPage3(Bundle savedInstanceState) {
        mConfirmation = (TextView) mPages.findViewById(R.id.confirm);
    }

    void preparePage() {
        switch (mCurrentPage) {
        case PAGE_SELECT_ACCOUNT:
            if (shouldDeleteBookmarks()) {
                mSelectAccountDescription.setText(
                        R.string.import_bookmarks_dialog_delete_select_account);
            } else {
                mSelectAccountDescription.setText(
                        R.string.import_bookmarks_dialog_select_add_account);
            }
            break;
        case PAGE_CONFIRMATION:
            String account = getSelectedAccount().name;
            String confirmationMessage;
            if (shouldDeleteBookmarks()) {
                confirmationMessage = getActivity().getString(
                        R.string.import_bookmarks_dialog_confirm_delete, account);
            } else {
                confirmationMessage = getActivity().getString(
                        R.string.import_bookmarks_dialog_confirm_add, account);
            }
            mConfirmation.setText(confirmationMessage);
            break;
        }
    }

    int getAdjustedCheckedItemPosition(ListView list) {
        int position = list.getCheckedItemPosition();
        if (position != ListView.INVALID_POSITION) {
            position -= list.getHeaderViewsCount();
        }
        return position;
    }

    Account getSelectedAccount() {
        return mAccounts[getAdjustedCheckedItemPosition(mSelectAccount)];
    }

    boolean shouldDeleteBookmarks() {
        return getAdjustedCheckedItemPosition(mImportOrDelete) == 1;
    }

    @Override
    public void onItemClick(
            AdapterView<?> parent, View view, int position, long id) {
        validate();
    }

    void updateNavigation() {
        if (mCurrentPage == 0) {
            mNegativeButton.setText(R.string.import_bookmarks_wizard_cancel);
        } else {
            mNegativeButton.setText(R.string.import_bookmarks_wizard_previous);
        }
        if ((mCurrentPage + 1) == mPages.getChildCount()) {
            mPositiveButton.setText(R.string.import_bookmarks_wizard_done);
        } else {
            mPositiveButton.setText(R.string.import_bookmarks_wizard_next);
        }
        validate();
    }

    void validate() {
        switch (mCurrentPage) {
        case PAGE_IMPORT_OR_DELETE:
            mPositiveButton.setEnabled(
                    mImportOrDelete.getCheckedItemPosition() != ListView.INVALID_POSITION);
            break;
        case PAGE_SELECT_ACCOUNT:
            mPositiveButton.setEnabled(
                    mSelectAccount.getCheckedItemPosition() != ListView.INVALID_POSITION);
            break;
        }
    }

    void setupAnimations() {
        float animX = mPages.getMeasuredWidth();
        final LayoutTransition transitioner = new LayoutTransition();
        ObjectAnimator appearing = ObjectAnimator.ofFloat(this, "translationX",
                animX, 0);
        ObjectAnimator disappearing = ObjectAnimator.ofFloat(this, "translationX",
                0, animX);
        transitioner.setAnimator(LayoutTransition.APPEARING, appearing);
        transitioner.setAnimator(LayoutTransition.DISAPPEARING, disappearing);
        mPages.setLayoutTransition(transitioner);
    }

    boolean next() {
        if (mCurrentPage + 1 < mPages.getChildCount()) {
            mCurrentPage++;
            preparePage();
            mPages.getChildAt(mCurrentPage).setVisibility(View.VISIBLE);
            mPages.setTargetChild(mCurrentPage);
            return true;
        }
        return false;
    }

    boolean prev() {
        if (mCurrentPage > 0) {
            mPages.getChildAt(mCurrentPage).setVisibility(View.GONE);
            mCurrentPage--;
            mPages.setTargetChild(mCurrentPage);
            return true;
        }
        return false;
    }

    void done() {
        ContentResolver resolver = getActivity().getContentResolver();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String accountName = getSelectedAccount().name;
        if (shouldDeleteBookmarks()) {
            // The user chose to remove their old bookmarks, delete them now
            resolver.delete(Bookmarks.CONTENT_URI,
                    Bookmarks.PARENT + "=1 AND " + Bookmarks.ACCOUNT_NAME + " IS NULL", null);
        } else {
            // The user chose to migrate their old bookmarks to the account they're syncing
            migrateBookmarks(resolver, accountName);
        }

        // Record the fact that we turned on sync
        BrowserContract.Settings.setSyncEnabled(getActivity(), true);
        prefs.edit()
                .putString(BrowserBookmarksPage.PREF_ACCOUNT_TYPE, "com.google")
                .putString(BrowserBookmarksPage.PREF_ACCOUNT_NAME, accountName)
                .apply();

        // Enable bookmark sync on all accounts
        Account[] accounts = (Account[]) getArguments().getParcelableArray("accounts");
        for (Account account : accounts) {
            if (ContentResolver.getIsSyncable(account, BrowserContract.AUTHORITY) == 0) {
                // Account wasn't syncable, enable it
                ContentResolver.setIsSyncable(account, BrowserContract.AUTHORITY, 1);
                ContentResolver.setSyncAutomatically(account, BrowserContract.AUTHORITY, true);
            }
        }

        dismiss();
    }

    @Override
    public void onClick(View v) {
        if (v == mNegativeButton) {
            if (prev()) {
                updateNavigation();
            } else {
                dismiss();
            }
        } else if (v == mPositiveButton) {
            if (next()) {
                updateNavigation();
            } else {
                done();
            }
        }
    }

    /**
     * Migrates bookmarks to the given account
     */
    void migrateBookmarks(ContentResolver resolver, String accountName) {
        Cursor cursor = null;
        try {
            // Re-parent the bookmarks in the default root folder
            cursor = resolver.query(Bookmarks.CONTENT_URI, new String[] { Bookmarks._ID },
                    Bookmarks.ACCOUNT_NAME + " =? AND " +
                        ChromeSyncColumns.SERVER_UNIQUE + " =?",
                    new String[] { accountName,
                        ChromeSyncColumns.FOLDER_NAME_BOOKMARKS_BAR },
                    null);
            ContentValues values = new ContentValues();
            if (cursor == null || !cursor.moveToFirst()) {
                // The root folders don't exist for the account, create them now
                ArrayList<ContentProviderOperation> ops =
                        new ArrayList<ContentProviderOperation>();

                // Chrome sync root folder
                values.clear();
                values.put(ChromeSyncColumns.SERVER_UNIQUE, ChromeSyncColumns.FOLDER_NAME_ROOT);
                values.put(Bookmarks.TITLE, "Google Chrome");
                values.put(Bookmarks.POSITION, 0);
                values.put(Bookmarks.IS_FOLDER, true);
                values.put(Bookmarks.DIRTY, true);
                ops.add(ContentProviderOperation.newInsert(
                        Bookmarks.CONTENT_URI.buildUpon().appendQueryParameter(
                                BrowserContract.CALLER_IS_SYNCADAPTER, "true").build())
                        .withValues(values)
                        .build());

                // Bookmarks folder
                values.clear();
                values.put(ChromeSyncColumns.SERVER_UNIQUE,
                        ChromeSyncColumns.FOLDER_NAME_BOOKMARKS);
                values.put(Bookmarks.TITLE, "Bookmarks");
                values.put(Bookmarks.POSITION, 0);
                values.put(Bookmarks.IS_FOLDER, true);
                values.put(Bookmarks.DIRTY, true);
                ops.add(ContentProviderOperation.newInsert(Bookmarks.CONTENT_URI)
                        .withValues(values)
                        .withValueBackReference(Bookmarks.PARENT, 0)
                        .build());

                // Bookmarks Bar folder
                values.clear();
                values.put(ChromeSyncColumns.SERVER_UNIQUE,
                        ChromeSyncColumns.FOLDER_NAME_BOOKMARKS_BAR);
                values.put(Bookmarks.TITLE, "Bookmarks Bar");
                values.put(Bookmarks.POSITION, 0);
                values.put(Bookmarks.IS_FOLDER, true);
                values.put(Bookmarks.DIRTY, true);
                ops.add(ContentProviderOperation.newInsert(Bookmarks.CONTENT_URI)
                        .withValues(values)
                        .withValueBackReference(Bookmarks.PARENT, 1)
                        .build());

                // Other Bookmarks folder
                values.clear();
                values.put(ChromeSyncColumns.SERVER_UNIQUE,
                        ChromeSyncColumns.FOLDER_NAME_OTHER_BOOKMARKS);
                values.put(Bookmarks.TITLE, "Other Bookmarks");
                values.put(Bookmarks.POSITION, 1000);
                values.put(Bookmarks.IS_FOLDER, true);
                values.put(Bookmarks.DIRTY, true);
                ops.add(ContentProviderOperation.newInsert(Bookmarks.CONTENT_URI)
                        .withValues(values)
                        .withValueBackReference(Bookmarks.PARENT, 1)
                        .build());

                // Re-parent the existing bookmarks to the newly create bookmarks bar folder
                ops.add(ContentProviderOperation.newUpdate(Bookmarks.CONTENT_URI)
                        .withValueBackReference(Bookmarks.PARENT, 2)
                        .withSelection(Bookmarks.ACCOUNT_NAME + " IS NULL AND " +
                                Bookmarks.PARENT + "=?",
                                    new String[] { Integer.toString(1) })
                        .build());

                // Mark all non-root folder items as belonging to the new account
                values.clear();
                values.put(Bookmarks.ACCOUNT_TYPE, "com.google");
                values.put(Bookmarks.ACCOUNT_NAME, accountName);
                ops.add(ContentProviderOperation.newUpdate(Bookmarks.CONTENT_URI)
                        .withValues(values)
                        .withSelection(Bookmarks.ACCOUNT_NAME + " IS NULL AND " +
                                Bookmarks._ID + "<>1", null)
                        .build());

                try {
                    resolver.applyBatch(BrowserContract.AUTHORITY, ops);
                } catch (RemoteException e) {
                    Log.e(TAG, "failed to create root folder for account " + accountName, e);
                    return;
                } catch (OperationApplicationException e) {
                    Log.e(TAG, "failed to create root folder for account " + accountName, e);
                    return;
                }
            } else {
                values.put(Bookmarks.PARENT, cursor.getLong(0));
                resolver.update(Bookmarks.CONTENT_URI, values, Bookmarks.PARENT + "=?",
                        new String[] { Integer.toString(1) });

                // Mark all bookmarks at all levels as part of the new account
                values.clear();
                values.put(Bookmarks.ACCOUNT_TYPE, "com.google");
                values.put(Bookmarks.ACCOUNT_NAME, accountName);
                resolver.update(Bookmarks.CONTENT_URI, values,
                        Bookmarks.ACCOUNT_NAME + " IS NULL AND " + Bookmarks._ID + "<>1",
                        null);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}