/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.contacts;

import com.android.contacts.model.AccountType;
import com.android.contacts.model.AccountTypeManager;
import com.android.contacts.util.DataStatus;
import com.android.contacts.util.StreamItemEntry;
import com.android.contacts.util.StreamItemPhotoEntry;
import com.google.android.collect.Lists;
import com.google.common.annotations.VisibleForTesting;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.content.Entity.NamedContentValues;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Directory;
import android.provider.ContactsContract.DisplayNameSources;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.StreamItemPhotos;
import android.provider.ContactsContract.StreamItems;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a single Contact and all it constituent RawContacts.
 */
public class ContactLoader extends Loader<ContactLoader.Result> {
    private static final String TAG = "ContactLoader";

    private Uri mLookupUri;
    private boolean mLoadGroupMetaData;
    private boolean mLoadStreamItems;
    private final boolean mLoadInvitableAccountTypes;
    private Result mContact;
    private ForceLoadContentObserver mObserver;
    private boolean mDestroyed;


    public interface Listener {
        public void onContactLoaded(Result contact);
    }

    /**
     * The result of a load operation. Contains all data necessary to display the contact.
     */
    public static final class Result {
        /**
         * Singleton instance that represents "No Contact Found"
         */
        public static final Result NOT_FOUND = new Result();

        /**
         * Singleton instance that represents an error, e.g. because of an invalid Uri
         * TODO: We should come up with something nicer here. Maybe use an Either type so
         * that we can capture the Exception?
         */
        public static final Result ERROR = new Result();

        private final Uri mLookupUri;
        private final Uri mUri;
        private final long mDirectoryId;
        private final String mLookupKey;
        private final long mId;
        private final long mNameRawContactId;
        private final int mDisplayNameSource;
        private final long mPhotoId;
        private final String mPhotoUri;
        private final String mDisplayName;
        private final String mAltDisplayName;
        private final String mPhoneticName;
        private final boolean mStarred;
        private final Integer mPresence;
        private final ArrayList<Entity> mEntities;
        private ArrayList<StreamItemEntry> mStreamItems;
        private final HashMap<Long, DataStatus> mStatuses;
        private final ArrayList<AccountType> mInvitableAccountTypes;

        private String mDirectoryDisplayName;
        private String mDirectoryType;
        private String mDirectoryAccountType;
        private String mDirectoryAccountName;
        private int mDirectoryExportSupport;

        private ArrayList<GroupMetaData> mGroups;

        private boolean mLoadingPhoto;
        private byte[] mPhotoBinaryData;
        private boolean mSendToVoicemail;
        private String mCustomRingtone;
        private boolean mIsUserProfile;

        /**
         * Constructor for case "no contact found". This must only be used for the
         * final {@link Result#NOT_FOUND} singleton
         */
        private Result() {
            mLookupUri = null;
            mUri = null;
            mDirectoryId = -1;
            mLookupKey = null;
            mId = -1;
            mEntities = null;
            mStreamItems = new ArrayList<StreamItemEntry>();
            mStatuses = null;
            mNameRawContactId = -1;
            mDisplayNameSource = DisplayNameSources.UNDEFINED;
            mPhotoId = -1;
            mPhotoUri = null;
            mDisplayName = null;
            mAltDisplayName = null;
            mPhoneticName = null;
            mStarred = false;
            mPresence = null;
            mInvitableAccountTypes = null;
            mSendToVoicemail = false;
            mCustomRingtone = null;
            mIsUserProfile = false;

        }

        /**
         * Constructor to call when contact was found
         */
        private Result(Uri uri, Uri lookupUri, long directoryId, String lookupKey, long id,
                long nameRawContactId, int displayNameSource, long photoId, String photoUri,
                String displayName, String altDisplayName, String phoneticName, boolean starred,
                Integer presence, boolean sendToVoicemail, String customRingtone,
                boolean isUserProfile) {
            mLookupUri = lookupUri;
            mUri = uri;
            mDirectoryId = directoryId;
            mLookupKey = lookupKey;
            mId = id;
            mEntities = new ArrayList<Entity>();
            mStreamItems = new ArrayList<StreamItemEntry>();
            mStatuses = new HashMap<Long, DataStatus>();
            mNameRawContactId = nameRawContactId;
            mDisplayNameSource = displayNameSource;
            mPhotoId = photoId;
            mPhotoUri = photoUri;
            mDisplayName = displayName;
            mAltDisplayName = altDisplayName;
            mPhoneticName = phoneticName;
            mStarred = starred;
            mPresence = presence;
            mInvitableAccountTypes = Lists.newArrayList();
            mSendToVoicemail = sendToVoicemail;
            mCustomRingtone = customRingtone;
            mIsUserProfile = isUserProfile;
        }

        private Result(Result from) {
            mLookupUri = from.mLookupUri;
            mUri = from.mUri;
            mDirectoryId = from.mDirectoryId;
            mLookupKey = from.mLookupKey;
            mId = from.mId;
            mNameRawContactId = from.mNameRawContactId;
            mDisplayNameSource = from.mDisplayNameSource;
            mPhotoId = from.mPhotoId;
            mPhotoUri = from.mPhotoUri;
            mDisplayName = from.mDisplayName;
            mAltDisplayName = from.mAltDisplayName;
            mPhoneticName = from.mPhoneticName;
            mStarred = from.mStarred;
            mPresence = from.mPresence;
            mEntities = from.mEntities;
            mStreamItems = from.mStreamItems;
            mStatuses = from.mStatuses;
            mInvitableAccountTypes = from.mInvitableAccountTypes;

            mDirectoryDisplayName = from.mDirectoryDisplayName;
            mDirectoryType = from.mDirectoryType;
            mDirectoryAccountType = from.mDirectoryAccountType;
            mDirectoryAccountName = from.mDirectoryAccountName;
            mDirectoryExportSupport = from.mDirectoryExportSupport;

            mGroups = from.mGroups;

            mLoadingPhoto = from.mLoadingPhoto;
            mPhotoBinaryData = from.mPhotoBinaryData;
            mSendToVoicemail = from.mSendToVoicemail;
            mCustomRingtone = from.mCustomRingtone;
            mIsUserProfile = from.mIsUserProfile;
        }

        /**
         * @param exportSupport See {@link Directory#EXPORT_SUPPORT}.
         */
        private void setDirectoryMetaData(String displayName, String directoryType,
                String accountType, String accountName, int exportSupport) {
            mDirectoryDisplayName = displayName;
            mDirectoryType = directoryType;
            mDirectoryAccountType = accountType;
            mDirectoryAccountName = accountName;
            mDirectoryExportSupport = exportSupport;
        }

        private void setLoadingPhoto(boolean flag) {
            mLoadingPhoto = flag;
        }

        private void setPhotoBinaryData(byte[] photoBinaryData) {
            mPhotoBinaryData = photoBinaryData;
        }

        public Uri getLookupUri() {
            return mLookupUri;
        }

        public String getLookupKey() {
            return mLookupKey;
        }

        public Uri getUri() {
            return mUri;
        }

        @VisibleForTesting
        /*package*/ long getId() {
            return mId;
        }

        public long getNameRawContactId() {
            return mNameRawContactId;
        }

        public int getDisplayNameSource() {
            return mDisplayNameSource;
        }

        public long getPhotoId() {
            return mPhotoId;
        }

        public String getPhotoUri() {
            return mPhotoUri;
        }

        public String getDisplayName() {
            return mDisplayName;
        }

        public String getAltDisplayName() {
            return mAltDisplayName;
        }

        public String getPhoneticName() {
            return mPhoneticName;
        }

        public boolean getStarred() {
            return mStarred;
        }

        public Integer getPresence() {
            return mPresence;
        }

        public ArrayList<AccountType> getInvitableAccountTypes() {
            return mInvitableAccountTypes;
        }

        public ArrayList<Entity> getEntities() {
            return mEntities;
        }

        public ArrayList<StreamItemEntry> getStreamItems() {
            return mStreamItems;
        }

        public HashMap<Long, DataStatus> getStatuses() {
            return mStatuses;
        }

        public long getDirectoryId() {
            return mDirectoryId;
        }

        public boolean isDirectoryEntry() {
            return mDirectoryId != -1 && mDirectoryId != Directory.DEFAULT
                    && mDirectoryId != Directory.LOCAL_INVISIBLE;
        }

        public int getDirectoryExportSupport() {
            return mDirectoryExportSupport;
        }

        public String getDirectoryDisplayName() {
            return mDirectoryDisplayName;
        }

        public String getDirectoryType() {
            return mDirectoryType;
        }

        public String getDirectoryAccountType() {
            return mDirectoryAccountType;
        }

        public String getDirectoryAccountName() {
            return mDirectoryAccountName;
        }

        public boolean isLoadingPhoto() {
            return mLoadingPhoto;
        }

        public byte[] getPhotoBinaryData() {
            return mPhotoBinaryData;
        }

        public ArrayList<ContentValues> getContentValues() {
            if (mEntities.size() != 1) {
                throw new IllegalStateException(
                        "Cannot extract content values from an aggregated contact");
            }

            Entity entity = mEntities.get(0);
            ArrayList<ContentValues> result = new ArrayList<ContentValues>();
            ArrayList<NamedContentValues> subValues = entity.getSubValues();
            if (subValues != null) {
                int size = subValues.size();
                for (int i = 0; i < size; i++) {
                    NamedContentValues pair = subValues.get(i);
                    if (Data.CONTENT_URI.equals(pair.uri)) {
                        result.add(pair.values);
                    }
                }
            }

            // If the photo was loaded using the URI, create an entry for the photo
            // binary data.
            if (mPhotoId == 0 && mPhotoBinaryData != null) {
                ContentValues photo = new ContentValues();
                photo.put(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE);
                photo.put(Photo.PHOTO, mPhotoBinaryData);
                result.add(photo);
            }

            return result;
        }

        private void addGroupMetaData(GroupMetaData group) {
            if (mGroups == null) {
                mGroups = new ArrayList<GroupMetaData>();
            }
            mGroups.add(group);
        }

        public List<GroupMetaData> getGroupMetaData() {
            return mGroups;
        }

        public boolean isSendToVoicemail() {
            return mSendToVoicemail;
        }

        public String getCustomRingtone() {
            return mCustomRingtone;
        }

        public boolean isUserProfile() {
            return mIsUserProfile;
        }
    }

    /**
     * Projection used for the query that loads all data for the entire contact (except for
     * social stream items).
     */
    private static class ContactQuery {
        final static String[] COLUMNS = new String[] {
                Contacts.NAME_RAW_CONTACT_ID,
                Contacts.DISPLAY_NAME_SOURCE,
                Contacts.LOOKUP_KEY,
                Contacts.DISPLAY_NAME,
                Contacts.DISPLAY_NAME_ALTERNATIVE,
                Contacts.PHONETIC_NAME,
                Contacts.PHOTO_ID,
                Contacts.STARRED,
                Contacts.CONTACT_PRESENCE,
                Contacts.CONTACT_STATUS,
                Contacts.CONTACT_STATUS_TIMESTAMP,
                Contacts.CONTACT_STATUS_RES_PACKAGE,
                Contacts.CONTACT_STATUS_LABEL,
                Contacts.Entity.CONTACT_ID,
                Contacts.Entity.RAW_CONTACT_ID,

                RawContacts.ACCOUNT_NAME,
                RawContacts.ACCOUNT_TYPE,
                RawContacts.DATA_SET,
                RawContacts.ACCOUNT_TYPE_AND_DATA_SET,
                RawContacts.DIRTY,
                RawContacts.VERSION,
                RawContacts.SOURCE_ID,
                RawContacts.SYNC1,
                RawContacts.SYNC2,
                RawContacts.SYNC3,
                RawContacts.SYNC4,
                RawContacts.DELETED,
                RawContacts.NAME_VERIFIED,

                Contacts.Entity.DATA_ID,
                Data.DATA1,
                Data.DATA2,
                Data.DATA3,
                Data.DATA4,
                Data.DATA5,
                Data.DATA6,
                Data.DATA7,
                Data.DATA8,
                Data.DATA9,
                Data.DATA10,
                Data.DATA11,
                Data.DATA12,
                Data.DATA13,
                Data.DATA14,
                Data.DATA15,
                Data.SYNC1,
                Data.SYNC2,
                Data.SYNC3,
                Data.SYNC4,
                Data.DATA_VERSION,
                Data.IS_PRIMARY,
                Data.IS_SUPER_PRIMARY,
                Data.MIMETYPE,
                Data.RES_PACKAGE,

                GroupMembership.GROUP_SOURCE_ID,

                Data.PRESENCE,
                Data.CHAT_CAPABILITY,
                Data.STATUS,
                Data.STATUS_RES_PACKAGE,
                Data.STATUS_ICON,
                Data.STATUS_LABEL,
                Data.STATUS_TIMESTAMP,

                Contacts.PHOTO_URI,
                Contacts.SEND_TO_VOICEMAIL,
                Contacts.CUSTOM_RINGTONE,
                Contacts.IS_USER_PROFILE,
        };

        public final static int NAME_RAW_CONTACT_ID = 0;
        public final static int DISPLAY_NAME_SOURCE = 1;
        public final static int LOOKUP_KEY = 2;
        public final static int DISPLAY_NAME = 3;
        public final static int ALT_DISPLAY_NAME = 4;
        public final static int PHONETIC_NAME = 5;
        public final static int PHOTO_ID = 6;
        public final static int STARRED = 7;
        public final static int CONTACT_PRESENCE = 8;
        public final static int CONTACT_STATUS = 9;
        public final static int CONTACT_STATUS_TIMESTAMP = 10;
        public final static int CONTACT_STATUS_RES_PACKAGE = 11;
        public final static int CONTACT_STATUS_LABEL = 12;
        public final static int CONTACT_ID = 13;
        public final static int RAW_CONTACT_ID = 14;

        public final static int ACCOUNT_NAME = 15;
        public final static int ACCOUNT_TYPE = 16;
        public final static int DATA_SET = 17;
        public final static int ACCOUNT_TYPE_AND_DATA_SET = 18;
        public final static int DIRTY = 19;
        public final static int VERSION = 20;
        public final static int SOURCE_ID = 21;
        public final static int SYNC1 = 22;
        public final static int SYNC2 = 23;
        public final static int SYNC3 = 24;
        public final static int SYNC4 = 25;
        public final static int DELETED = 26;
        public final static int NAME_VERIFIED = 27;

        public final static int DATA_ID = 28;
        public final static int DATA1 = 29;
        public final static int DATA2 = 30;
        public final static int DATA3 = 31;
        public final static int DATA4 = 32;
        public final static int DATA5 = 33;
        public final static int DATA6 = 34;
        public final static int DATA7 = 35;
        public final static int DATA8 = 36;
        public final static int DATA9 = 37;
        public final static int DATA10 = 38;
        public final static int DATA11 = 39;
        public final static int DATA12 = 40;
        public final static int DATA13 = 41;
        public final static int DATA14 = 42;
        public final static int DATA15 = 43;
        public final static int DATA_SYNC1 = 44;
        public final static int DATA_SYNC2 = 45;
        public final static int DATA_SYNC3 = 46;
        public final static int DATA_SYNC4 = 47;
        public final static int DATA_VERSION = 48;
        public final static int IS_PRIMARY = 49;
        public final static int IS_SUPERPRIMARY = 50;
        public final static int MIMETYPE = 51;
        public final static int RES_PACKAGE = 52;

        public final static int GROUP_SOURCE_ID = 53;

        public final static int PRESENCE = 54;
        public final static int CHAT_CAPABILITY = 55;
        public final static int STATUS = 56;
        public final static int STATUS_RES_PACKAGE = 57;
        public final static int STATUS_ICON = 58;
        public final static int STATUS_LABEL = 59;
        public final static int STATUS_TIMESTAMP = 60;

        public final static int PHOTO_URI = 61;
        public final static int SEND_TO_VOICEMAIL = 62;
        public final static int CUSTOM_RINGTONE = 63;
        public final static int IS_USER_PROFILE = 64;
    }

    /**
     * Projection used for the query that loads all data for the entire contact.
     */
    private static class DirectoryQuery {
        final static String[] COLUMNS = new String[] {
            Directory.DISPLAY_NAME,
            Directory.PACKAGE_NAME,
            Directory.TYPE_RESOURCE_ID,
            Directory.ACCOUNT_TYPE,
            Directory.ACCOUNT_NAME,
            Directory.EXPORT_SUPPORT,
        };

        public final static int DISPLAY_NAME = 0;
        public final static int PACKAGE_NAME = 1;
        public final static int TYPE_RESOURCE_ID = 2;
        public final static int ACCOUNT_TYPE = 3;
        public final static int ACCOUNT_NAME = 4;
        public final static int EXPORT_SUPPORT = 5;
    }

    private static class GroupQuery {
        final static String[] COLUMNS = new String[] {
            Groups.ACCOUNT_NAME,
            Groups.ACCOUNT_TYPE,
            Groups.DATA_SET,
            Groups.ACCOUNT_TYPE_AND_DATA_SET,
            Groups._ID,
            Groups.TITLE,
            Groups.AUTO_ADD,
            Groups.FAVORITES,
        };

        public final static int ACCOUNT_NAME = 0;
        public final static int ACCOUNT_TYPE = 1;
        public final static int DATA_SET = 2;
        public final static int ACCOUNT_TYPE_AND_DATA_SET = 3;
        public final static int ID = 4;
        public final static int TITLE = 5;
        public final static int AUTO_ADD = 6;
        public final static int FAVORITES = 7;
    }

    private final class LoadContactTask extends AsyncTask<Void, Void, Result> {

        @Override
        protected Result doInBackground(Void... args) {
            try {
                final ContentResolver resolver = getContext().getContentResolver();
                final Uri uriCurrentFormat = ensureIsContactUri(resolver, mLookupUri);
                Result result = loadContactEntity(resolver, uriCurrentFormat);
                if (result != Result.NOT_FOUND) {
                    if (result.isDirectoryEntry()) {
                        loadDirectoryMetaData(result);
                    } else if (mLoadGroupMetaData) {
                        loadGroupMetaData(result);
                    }
                    if (mLoadStreamItems) {
                        loadStreamItems(result);
                    }
                    loadPhotoBinaryData(result);
                    if (mLoadInvitableAccountTypes) {
                        loadInvitableAccountTypes(result);
                    }
                }
                return result;
            } catch (Exception e) {
                Log.e(TAG, "Error loading the contact: " + mLookupUri, e);
                return Result.ERROR;
            }
        }

        /**
         * Transforms the given Uri and returns a Lookup-Uri that represents the contact.
         * For legacy contacts, a raw-contact lookup is performed.
         * @param resolver
         */
        private Uri ensureIsContactUri(final ContentResolver resolver, final Uri uri) {
            if (uri == null) throw new IllegalArgumentException("uri must not be null");

            final String authority = uri.getAuthority();

            // Current Style Uri?
            if (ContactsContract.AUTHORITY.equals(authority)) {
                final String type = resolver.getType(uri);
                // Contact-Uri? Good, return it
                if (Contacts.CONTENT_ITEM_TYPE.equals(type)) {
                    return uri;
                }

                // RawContact-Uri? Transform it to ContactUri
                if (RawContacts.CONTENT_ITEM_TYPE.equals(type)) {
                    final long rawContactId = ContentUris.parseId(uri);
                    return RawContacts.getContactLookupUri(getContext().getContentResolver(),
                            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId));
                }

                // Anything else? We don't know what this is
                throw new IllegalArgumentException("uri format is unknown");
            }

            // Legacy Style? Convert to RawContact
            final String OBSOLETE_AUTHORITY = "contacts";
            if (OBSOLETE_AUTHORITY.equals(authority)) {
                // Legacy Format. Convert to RawContact-Uri and then lookup the contact
                final long rawContactId = ContentUris.parseId(uri);
                return RawContacts.getContactLookupUri(resolver,
                        ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId));
            }

            throw new IllegalArgumentException("uri authority is unknown");
        }

        private Result loadContactEntity(ContentResolver resolver, Uri contactUri) {
            Uri entityUri = Uri.withAppendedPath(contactUri, Contacts.Entity.CONTENT_DIRECTORY);
            Cursor cursor = resolver.query(entityUri, ContactQuery.COLUMNS, null, null,
                    Contacts.Entity.RAW_CONTACT_ID);
            if (cursor == null) {
                Log.e(TAG, "No cursor returned in loadContactEntity");
                return Result.NOT_FOUND;
            }

            try {
                if (!cursor.moveToFirst()) {
                    cursor.close();
                    return Result.NOT_FOUND;
                }

                long currentRawContactId = -1;
                Entity entity = null;
                Result result = loadContactHeaderData(cursor, contactUri);
                ArrayList<Entity> entities = result.getEntities();
                HashMap<Long, DataStatus> statuses = result.getStatuses();
                for (; !cursor.isAfterLast(); cursor.moveToNext()) {
                    long rawContactId = cursor.getLong(ContactQuery.RAW_CONTACT_ID);
                    if (rawContactId != currentRawContactId) {
                        currentRawContactId = rawContactId;
                        entity = new android.content.Entity(loadRawContact(cursor));
                        entities.add(entity);
                    }
                    if (!cursor.isNull(ContactQuery.DATA_ID)) {
                        ContentValues data = loadData(cursor);
                        entity.addSubValue(ContactsContract.Data.CONTENT_URI, data);

                        if (!cursor.isNull(ContactQuery.PRESENCE)
                                || !cursor.isNull(ContactQuery.STATUS)) {
                            final DataStatus status = new DataStatus(cursor);
                            final long dataId = cursor.getLong(ContactQuery.DATA_ID);
                            statuses.put(dataId, status);
                        }
                    }
                }

                return result;
            } finally {
                cursor.close();
            }
        }

        /**
         * Looks for the photo data item in entities. If found, creates a new Bitmap instance. If
         * not found, returns null
         */
        private void loadPhotoBinaryData(Result contactData) {

            // If we have a photo URI, try loading that first.
            String photoUri = contactData.getPhotoUri();
            if (photoUri != null) {
                try {
                    AssetFileDescriptor fd = getContext().getContentResolver()
                           .openAssetFileDescriptor(Uri.parse(photoUri), "r");
                    byte[] buffer = new byte[16 * 1024];
                    FileInputStream fis = fd.createInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try {
                        int size;
                        while ((size = fis.read(buffer)) != -1) {
                            baos.write(buffer, 0, size);
                        }
                        contactData.setPhotoBinaryData(baos.toByteArray());
                    } finally {
                        fis.close();
                        fd.close();
                    }
                    return;
                } catch (IOException ioe) {
                    // Just fall back to the case below.
                }
            }

            // If we couldn't load from a file, fall back to the data blob.
            final long photoId = contactData.getPhotoId();
            if (photoId <= 0) {
                // No photo ID
                return;
            }

            for (Entity entity : contactData.getEntities()) {
                for (NamedContentValues subValue : entity.getSubValues()) {
                    final ContentValues entryValues = subValue.values;
                    final long dataId = entryValues.getAsLong(Data._ID);
                    if (dataId == photoId) {
                        final String mimeType = entryValues.getAsString(Data.MIMETYPE);
                        // Correct Data Id but incorrect MimeType? Don't load
                        if (!Photo.CONTENT_ITEM_TYPE.equals(mimeType)) {
                            return;
                        }
                        contactData.setPhotoBinaryData(entryValues.getAsByteArray(Photo.PHOTO));
                        break;
                    }
                }
            }
        }

        /**
         * Sets the "invitable" account types to {@link Result#mInvitableAccountTypes}.
         *
         * TODO Exclude the ones with no raw contacts in the database.
         */
        private void loadInvitableAccountTypes(Result contactData) {
            Map<String, AccountType> allInvitables =
                    AccountTypeManager.getInstance(getContext()).getInvitableAccountTypes();
            if (allInvitables.isEmpty()) {
                return;
            }

            HashMap<String, AccountType> result = new HashMap<String, AccountType>(allInvitables);

            // Remove the ones that already have a raw contact in the current contact
            for (Entity entity : contactData.getEntities()) {
                final String type = entity.getEntityValues().getAsString(RawContacts.ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(type)) {
                    result.remove(type);
                }
            }

            // Set to mInvitableAccountTypes
            contactData.mInvitableAccountTypes.addAll(result.values());
        }

        /**
         * Extracts Contact level columns from the cursor.
         */
        private Result loadContactHeaderData(final Cursor cursor, Uri contactUri) {
            final String directoryParameter =
                    contactUri.getQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY);
            final long directoryId = directoryParameter == null
                    ? Directory.DEFAULT
                    : Long.parseLong(directoryParameter);
            final long contactId = cursor.getLong(ContactQuery.CONTACT_ID);
            final String lookupKey = cursor.getString(ContactQuery.LOOKUP_KEY);
            final long nameRawContactId = cursor.getLong(ContactQuery.NAME_RAW_CONTACT_ID);
            final int displayNameSource = cursor.getInt(ContactQuery.DISPLAY_NAME_SOURCE);
            final String displayName = cursor.getString(ContactQuery.DISPLAY_NAME);
            final String altDisplayName = cursor.getString(ContactQuery.ALT_DISPLAY_NAME);
            final String phoneticName = cursor.getString(ContactQuery.PHONETIC_NAME);
            final long photoId = cursor.getLong(ContactQuery.PHOTO_ID);
            final String photoUri = cursor.getString(ContactQuery.PHOTO_URI);
            final boolean starred = cursor.getInt(ContactQuery.STARRED) != 0;
            final Integer presence = cursor.isNull(ContactQuery.CONTACT_PRESENCE)
                    ? null
                    : cursor.getInt(ContactQuery.CONTACT_PRESENCE);
            final boolean sendToVoicemail = cursor.getInt(ContactQuery.SEND_TO_VOICEMAIL) == 1;
            final String customRingtone = cursor.getString(ContactQuery.CUSTOM_RINGTONE);
            final boolean isUserProfile = cursor.getInt(ContactQuery.IS_USER_PROFILE) == 1;

            Uri lookupUri;
            if (directoryId == Directory.DEFAULT || directoryId == Directory.LOCAL_INVISIBLE) {
                lookupUri = ContentUris.withAppendedId(
                    Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey), contactId);
            } else {
                lookupUri = contactUri;
            }

            return new Result(contactUri, lookupUri, directoryId, lookupKey, contactId,
                    nameRawContactId, displayNameSource, photoId, photoUri, displayName,
                    altDisplayName, phoneticName, starred, presence, sendToVoicemail,
                    customRingtone, isUserProfile);
        }

        /**
         * Extracts RawContact level columns from the cursor.
         */
        private ContentValues loadRawContact(Cursor cursor) {
            ContentValues cv = new ContentValues();

            cv.put(RawContacts._ID, cursor.getLong(ContactQuery.RAW_CONTACT_ID));

            cursorColumnToContentValues(cursor, cv, ContactQuery.ACCOUNT_NAME);
            cursorColumnToContentValues(cursor, cv, ContactQuery.ACCOUNT_TYPE);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA_SET);
            cursorColumnToContentValues(cursor, cv, ContactQuery.ACCOUNT_TYPE_AND_DATA_SET);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DIRTY);
            cursorColumnToContentValues(cursor, cv, ContactQuery.VERSION);
            cursorColumnToContentValues(cursor, cv, ContactQuery.SOURCE_ID);
            cursorColumnToContentValues(cursor, cv, ContactQuery.SYNC1);
            cursorColumnToContentValues(cursor, cv, ContactQuery.SYNC2);
            cursorColumnToContentValues(cursor, cv, ContactQuery.SYNC3);
            cursorColumnToContentValues(cursor, cv, ContactQuery.SYNC4);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DELETED);
            cursorColumnToContentValues(cursor, cv, ContactQuery.CONTACT_ID);
            cursorColumnToContentValues(cursor, cv, ContactQuery.STARRED);
            cursorColumnToContentValues(cursor, cv, ContactQuery.NAME_VERIFIED);

            return cv;
        }

        /**
         * Extracts Data level columns from the cursor.
         */
        private ContentValues loadData(Cursor cursor) {
            ContentValues cv = new ContentValues();

            cv.put(Data._ID, cursor.getLong(ContactQuery.DATA_ID));

            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA1);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA2);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA3);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA4);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA5);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA6);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA7);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA8);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA9);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA10);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA11);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA12);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA13);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA14);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA15);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA_SYNC1);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA_SYNC2);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA_SYNC3);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA_SYNC4);
            cursorColumnToContentValues(cursor, cv, ContactQuery.DATA_VERSION);
            cursorColumnToContentValues(cursor, cv, ContactQuery.IS_PRIMARY);
            cursorColumnToContentValues(cursor, cv, ContactQuery.IS_SUPERPRIMARY);
            cursorColumnToContentValues(cursor, cv, ContactQuery.MIMETYPE);
            cursorColumnToContentValues(cursor, cv, ContactQuery.RES_PACKAGE);
            cursorColumnToContentValues(cursor, cv, ContactQuery.GROUP_SOURCE_ID);
            cursorColumnToContentValues(cursor, cv, ContactQuery.CHAT_CAPABILITY);

            return cv;
        }

        private void cursorColumnToContentValues(
                Cursor cursor, ContentValues values, int index) {
            switch (cursor.getType(index)) {
                case Cursor.FIELD_TYPE_NULL:
                    // don't put anything in the content values
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    values.put(ContactQuery.COLUMNS[index], cursor.getLong(index));
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    values.put(ContactQuery.COLUMNS[index], cursor.getString(index));
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    values.put(ContactQuery.COLUMNS[index], cursor.getBlob(index));
                    break;
                default:
                    throw new IllegalStateException("Invalid or unhandled data type");
            }
        }

        private void loadDirectoryMetaData(Result result) {
            long directoryId = result.getDirectoryId();

            Cursor cursor = getContext().getContentResolver().query(
                    ContentUris.withAppendedId(Directory.CONTENT_URI, directoryId),
                    DirectoryQuery.COLUMNS, null, null, null);
            if (cursor == null) {
                return;
            }
            try {
                if (cursor.moveToFirst()) {
                    final String displayName = cursor.getString(DirectoryQuery.DISPLAY_NAME);
                    final String packageName = cursor.getString(DirectoryQuery.PACKAGE_NAME);
                    final int typeResourceId = cursor.getInt(DirectoryQuery.TYPE_RESOURCE_ID);
                    final String accountType = cursor.getString(DirectoryQuery.ACCOUNT_TYPE);
                    final String accountName = cursor.getString(DirectoryQuery.ACCOUNT_NAME);
                    final int exportSupport = cursor.getInt(DirectoryQuery.EXPORT_SUPPORT);
                    String directoryType = null;
                    if (!TextUtils.isEmpty(packageName)) {
                        PackageManager pm = getContext().getPackageManager();
                        try {
                            Resources resources = pm.getResourcesForApplication(packageName);
                            directoryType = resources.getString(typeResourceId);
                        } catch (NameNotFoundException e) {
                            Log.w(TAG, "Contact directory resource not found: "
                                    + packageName + "." + typeResourceId);
                        }
                    }

                    result.setDirectoryMetaData(
                            displayName, directoryType, accountType, accountName, exportSupport);
                }
            } finally {
                cursor.close();
            }
        }

        /**
         * Loads groups meta-data for all groups associated with all constituent raw contacts'
         * accounts.
         */
        private void loadGroupMetaData(Result result) {
            StringBuilder selection = new StringBuilder();
            ArrayList<String> selectionArgs = new ArrayList<String>();
            for (Entity entity : result.mEntities) {
                ContentValues values = entity.getEntityValues();
                String accountName = values.getAsString(RawContacts.ACCOUNT_NAME);
                String accountType = values.getAsString(RawContacts.ACCOUNT_TYPE);
                String dataSet = values.getAsString(RawContacts.DATA_SET);
                if (accountName != null && accountType != null) {
                    if (selection.length() != 0) {
                        selection.append(" OR ");
                    }
                    selection.append(
                            "(" + Groups.ACCOUNT_NAME + "=? AND " + Groups.ACCOUNT_TYPE + "=?");
                    selectionArgs.add(accountName);
                    selectionArgs.add(accountType);

                    if (dataSet != null) {
                        selection.append(" AND " + Groups.DATA_SET + "=?");
                        selectionArgs.add(dataSet);
                    } else {
                        selection.append(" AND " + Groups.DATA_SET + " IS NULL");
                    }
                    selection.append(")");
                }
            }
            Cursor cursor = getContext().getContentResolver().query(Groups.CONTENT_URI,
                    GroupQuery.COLUMNS, selection.toString(), selectionArgs.toArray(new String[0]),
                    null);
            try {
                while (cursor.moveToNext()) {
                    final String accountName = cursor.getString(GroupQuery.ACCOUNT_NAME);
                    final String accountType = cursor.getString(GroupQuery.ACCOUNT_TYPE);
                    final String dataSet = cursor.getString(GroupQuery.DATA_SET);
                    final long groupId = cursor.getLong(GroupQuery.ID);
                    final String title = cursor.getString(GroupQuery.TITLE);
                    final boolean defaultGroup = cursor.isNull(GroupQuery.AUTO_ADD)
                            ? false
                            : cursor.getInt(GroupQuery.AUTO_ADD) != 0;
                    final boolean favorites = cursor.isNull(GroupQuery.FAVORITES)
                            ? false
                            : cursor.getInt(GroupQuery.FAVORITES) != 0;

                    result.addGroupMetaData(new GroupMetaData(
                            accountName, accountType, dataSet, groupId, title, defaultGroup,
                            favorites));
                }
            } finally {
                cursor.close();
            }
        }

        /**
         * Loads all stream items and stream item photos belonging to this contact.
         */
        private void loadStreamItems(Result result) {
            Cursor cursor = getContext().getContentResolver().query(
                    Contacts.CONTENT_LOOKUP_URI.buildUpon()
                            .appendPath(result.getLookupKey())
                            .appendPath(Contacts.StreamItems.CONTENT_DIRECTORY).build(),
                    null, null, null, null);
            Map<Long, StreamItemEntry> streamItemsById = new HashMap<Long, StreamItemEntry>();
            ArrayList<StreamItemEntry> streamItems = new ArrayList<StreamItemEntry>();
            try {
                while (cursor.moveToNext()) {
                    StreamItemEntry streamItem = new StreamItemEntry(cursor);
                    streamItemsById.put(streamItem.getId(), streamItem);
                    streamItems.add(streamItem);
                }
            } finally {
                cursor.close();
            }

            // Now retrieve any photo records associated with the stream items.
            String[] streamItemIdArr = new String[streamItems.size()];
            StringBuilder streamItemPhotoSelection = new StringBuilder();
            if (!streamItems.isEmpty()) {
                streamItemPhotoSelection.append(StreamItemPhotos.STREAM_ITEM_ID + " IN (");
                for (int i = 0; i < streamItems.size(); i++) {
                    if (i > 0) {
                        streamItemPhotoSelection.append(",");
                    }
                    streamItemPhotoSelection.append("?");
                    streamItemIdArr[i] = String.valueOf(streamItems.get(i).getId());
                }
                streamItemPhotoSelection.append(")");
                cursor = getContext().getContentResolver().query(StreamItems.CONTENT_PHOTO_URI,
                        null, streamItemPhotoSelection.toString(), streamItemIdArr,
                        StreamItemPhotos.STREAM_ITEM_ID);
                try {
                    while (cursor.moveToNext()) {
                        long streamItemId = cursor.getLong(
                                cursor.getColumnIndex(StreamItemPhotos.STREAM_ITEM_ID));
                        StreamItemEntry streamItem = streamItemsById.get(streamItemId);
                        streamItem.addPhoto(new StreamItemPhotoEntry(cursor));
                    }
                } finally {
                    cursor.close();
                }
            }

            // Set the sorted stream items on the result.
            Collections.sort(streamItems);
            result.mStreamItems.addAll(streamItems);
        }

        @Override
        protected void onPostExecute(Result result) {
            unregisterObserver();

            // The creator isn't interested in any further updates
            if (mDestroyed || result == null) {
                return;
            }

            mContact = result;

            if (result != Result.ERROR && result != Result.NOT_FOUND) {
                mLookupUri = result.getLookupUri();

                if (!result.isDirectoryEntry()) {
                    Log.i(TAG, "Registering content observer for " + mLookupUri);
                    if (mObserver == null) {
                        mObserver = new ForceLoadContentObserver();
                    }
                    getContext().getContentResolver().registerContentObserver(
                            mLookupUri, true, mObserver);
                }

                if (mContact.getPhotoBinaryData() == null && mContact.getPhotoUri() != null) {
                    mContact.setLoadingPhoto(true);
                    new AsyncPhotoLoader().execute(mContact.getPhotoUri());
                }

                // inform the source of the data that this contact is being looked at
                postViewNotificationToSyncAdapter();
            }

            deliverResult(mContact);
        }
    }

    /**
     * Posts a message to the contributing sync adapters that have opted-in, notifying them
     * that the contact has just been loaded
     */
    private void postViewNotificationToSyncAdapter() {
        Context context = getContext();
        for (Entity entity : mContact.getEntities()) {
            final ContentValues entityValues = entity.getEntityValues();
            final String type = entityValues.getAsString(RawContacts.ACCOUNT_TYPE);
            final String dataSet = entityValues.getAsString(RawContacts.DATA_SET);
            final AccountType accountType = AccountTypeManager.getInstance(context ).getAccountType(
                    type, dataSet);
            final String serviceName = accountType.getViewContactNotifyServiceClassName();
            final String resPackageName = accountType.resPackageName;
            if (!TextUtils.isEmpty(serviceName) && !TextUtils.isEmpty(resPackageName)) {
                final long rawContactId = entityValues.getAsLong(RawContacts.Entity._ID);
                final Uri uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId);
                final Intent intent = new Intent();
                intent.setClassName(resPackageName, serviceName);
                intent.setAction(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, RawContacts.CONTENT_ITEM_TYPE);
                try {
                    context.startService(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending message to source-app", e);
                }
            }
        }
    }

    private class AsyncPhotoLoader extends AsyncTask<String, Void, byte[]> {

        private static final int BUFFER_SIZE = 1024*16;

        @Override
        protected byte[] doInBackground(String... params) {
            Uri uri = Uri.parse(params[0]);
            byte[] data = null;
            try {
                InputStream is = getContext().getContentResolver().openInputStream(uri);
                if (is != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try {
                        byte[] mBuffer = new byte[BUFFER_SIZE];

                        int size;
                        while ((size = is.read(mBuffer)) != -1) {
                            baos.write(mBuffer, 0, size);
                        }
                        data = baos.toByteArray();
                    } finally {
                        is.close();
                    }
                } else {
                    Log.v(TAG, "Cannot load photo " + uri);
                }
            } catch (IOException e) {
                Log.e(TAG, "Cannot load photo " + uri, e);
            }

            return data;
        }

        @Override
        protected void onPostExecute(byte[] data) {
            if (mContact != null) {
                mContact = new Result(mContact);
                mContact.setPhotoBinaryData(data);
                mContact.setLoadingPhoto(false);
                deliverResult(mContact);
            }
        }
    }

    private void unregisterObserver() {
        if (mObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mObserver);
            mObserver = null;
        }
    }

    public ContactLoader(Context context, Uri lookupUri) {
        this(context, lookupUri, false, false, false);
    }

    public ContactLoader(Context context, Uri lookupUri, boolean loadGroupMetaData,
            boolean loadStreamItems, boolean loadInvitableAccountTypes) {
        super(context);
        mLookupUri = lookupUri;
        mLoadGroupMetaData = loadGroupMetaData;
        mLoadStreamItems = loadStreamItems;
        mLoadInvitableAccountTypes = loadInvitableAccountTypes;
    }

    public Uri getLookupUri() {
        return mLookupUri;
    }

    @Override
    protected void onStartLoading() {
        if (mContact != null) {
            deliverResult(mContact);
        }

        if (takeContentChanged() || mContact == null) {
            forceLoad();
        }
    }

    @Override
    protected void onForceLoad() {
        final LoadContactTask task = new LoadContactTask();
        task.execute((Void[])null);
    }

    @Override
    protected void onReset() {
        unregisterObserver();
        mContact = null;
        mDestroyed = true;
    }
}
