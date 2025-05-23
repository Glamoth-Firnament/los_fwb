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
 * limitations under the License.
 */

package com.android.server.pm;

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.Manifest;
import android.annotation.ColorRes;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IUserManager;
import android.os.IUserRestrictionsListener;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManager.EnforcingUser;
import android.os.UserManager.QuietModeFlag;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.security.GateKeeper;
import android.service.gatekeeper.IGateKeeperService;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.BundleUtils;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemService;
import com.android.server.am.UserState;
import com.android.server.pm.UserManagerInternal.UserLifecycleListener;
import com.android.server.pm.UserManagerInternal.UserRestrictionsListener;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityTaskManagerInternal;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for {@link UserManager}.
 *
 * Method naming convention:
 * <ul>
 * <li> Methods suffixed with "LAr" should be called within the {@link #mAppRestrictionsLock} lock.
 * <li> Methods suffixed with "LP" should be called within the {@link #mPackagesLock} lock.
 * <li> Methods suffixed with "LR" should be called within the {@link #mRestrictionsLock} lock.
 * <li> Methods suffixed with "LU" should be called within the {@link #mUsersLock} lock.
 * </ul>
 */
public class UserManagerService extends IUserManager.Stub {

    private static final String LOG_TAG = "UserManagerService";
    static final boolean DBG = false; // DO NOT SUBMIT WITH TRUE
    private static final boolean DBG_WITH_STACKTRACE = false; // DO NOT SUBMIT WITH TRUE
    // Can be used for manual testing of id recycling
    private static final boolean RELEASE_DELETED_USER_ID = false; // DO NOT SUBMIT WITH TRUE

    private static final String TAG_NAME = "name";
    private static final String TAG_ACCOUNT = "account";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_LAST_LOGGED_IN_FINGERPRINT = "lastLoggedInFingerprint";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_PRE_CREATED = "preCreated";
    private static final String ATTR_CONVERTED_FROM_PRE_CREATED = "convertedFromPreCreated";
    private static final String ATTR_GUEST_TO_REMOVE = "guestToRemove";
    private static final String ATTR_USER_VERSION = "version";
    private static final String ATTR_USER_TYPE_VERSION = "userTypeConfigVersion";
    private static final String ATTR_PROFILE_GROUP_ID = "profileGroupId";
    private static final String ATTR_PROFILE_BADGE = "profileBadge";
    private static final String ATTR_RESTRICTED_PROFILE_PARENT_ID = "restrictedProfileParentId";
    private static final String ATTR_SEED_ACCOUNT_NAME = "seedAccountName";
    private static final String ATTR_SEED_ACCOUNT_TYPE = "seedAccountType";
    private static final String TAG_GUEST_RESTRICTIONS = "guestRestrictions";
    private static final String TAG_USERS = "users";
    private static final String TAG_USER = "user";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_DEVICE_POLICY_RESTRICTIONS = "device_policy_restrictions";
    private static final String TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS =
            "device_policy_local_restrictions";
    private static final String TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS =
            "device_policy_global_restrictions";
    /** Legacy name for device owner id tag. */
    private static final String TAG_GLOBAL_RESTRICTION_OWNER_ID = "globalRestrictionOwnerUserId";
    private static final String TAG_DEVICE_OWNER_USER_ID = "deviceOwnerUserId";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
    private static final String TAG_SEED_ACCOUNT_OPTIONS = "seedAccountOptions";
    private static final String TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL =
            "lastRequestQuietModeEnabledCall";
    private static final String TAG_IGNORE_PREPARE_STORAGE_ERRORS =
            "ignorePrepareStorageErrors";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_VALUE_TYPE = "type";
    private static final String ATTR_MULTIPLE = "m";

    private static final String ATTR_TYPE_STRING_ARRAY = "sa";
    private static final String ATTR_TYPE_STRING = "s";
    private static final String ATTR_TYPE_BOOLEAN = "b";
    private static final String ATTR_TYPE_INTEGER = "i";
    private static final String ATTR_TYPE_BUNDLE = "B";
    private static final String ATTR_TYPE_BUNDLE_ARRAY = "BA";

    private static final String USER_INFO_DIR = "system" + File.separator + "users";
    private static final String USER_LIST_FILENAME = "userlist.xml";
    private static final String USER_PHOTO_FILENAME = "photo.png";
    private static final String USER_PHOTO_FILENAME_TMP = USER_PHOTO_FILENAME + ".tmp";

    private static final String RESTRICTIONS_FILE_PREFIX = "res_";
    private static final String XML_SUFFIX = ".xml";

    private static final int ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION =
            UserInfo.FLAG_MANAGED_PROFILE
            | UserInfo.FLAG_PROFILE
            | UserInfo.FLAG_EPHEMERAL
            | UserInfo.FLAG_RESTRICTED
            | UserInfo.FLAG_GUEST
            | UserInfo.FLAG_DEMO
            | UserInfo.FLAG_FULL;

    @VisibleForTesting
    static final int MIN_USER_ID = UserHandle.MIN_SECONDARY_USER_ID;

    // We need to keep process uid within Integer.MAX_VALUE.
    @VisibleForTesting
    static final int MAX_USER_ID = Integer.MAX_VALUE / UserHandle.PER_USER_RANGE;

    // Max size of the queue of recently removed users
    @VisibleForTesting
    static final int MAX_RECENTLY_REMOVED_IDS_SIZE = 100;

    private static final int USER_VERSION = 9;

    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // ms

    static final int WRITE_USER_MSG = 1;
    static final int WRITE_USER_DELAY = 2*1000;  // 2 seconds

    // Tron counters
    private static final String TRON_GUEST_CREATED = "users_guest_created";
    private static final String TRON_USER_CREATED = "users_user_created";
    private static final String TRON_DEMO_CREATED = "users_demo_created";

    private final Context mContext;
    private final PackageManagerService mPm;

    /**
     * Lock for packages. If using with {@link #mUsersLock}, {@link #mPackagesLock} should be
     * acquired first.
     */
    private final Object mPackagesLock;
    private final UserDataPreparer mUserDataPreparer;
    /**
     * Short-term lock for internal state, when interaction/sync with PM is not required. If using
     * with {@link #mPackagesLock}, {@link #mPackagesLock} should be acquired first.
     */
    private final Object mUsersLock = LockGuard.installNewLock(LockGuard.INDEX_USER);
    private final Object mRestrictionsLock = new Object();
    // Used for serializing access to app restriction files
    private final Object mAppRestrictionsLock = new Object();

    private final Handler mHandler;

    private final File mUsersDir;
    private final File mUserListFile;

    private static final IBinder mUserRestriconToken = new Binder();

    /** Installs system packages based on user-type. */
    private final UserSystemPackageInstaller mSystemPackageInstaller;

    private PackageManagerInternal mPmInternal;
    private DevicePolicyManagerInternal mDevicePolicyManagerInternal;

    /**
     * Internal non-parcelable wrapper for UserInfo that is not exposed to other system apps.
     */
    @VisibleForTesting
    static class UserData {
        // Basic user information and properties
        UserInfo info;
        // Account name used when there is a strong association between a user and an account
        String account;
        // Account information for seeding into a newly created user. This could also be
        // used for login validation for an existing user, for updating their credentials.
        // In the latter case, data may not need to be persisted as it is only valid for the
        // current login session.
        String seedAccountName;
        String seedAccountType;
        PersistableBundle seedAccountOptions;
        // Whether to perist the seed account information to be available after a boot
        boolean persistSeedData;

        /** Elapsed realtime since boot when the user started. */
        long startRealtime;

        /** Elapsed realtime since boot when the user was unlocked. */
        long unlockRealtime;

        private long mLastRequestQuietModeEnabledMillis;

        /**
         * {@code true} if the system should ignore errors when preparing the
         * storage directories for this user. This is {@code false} for all new
         * users; it will only be {@code true} for users that already existed
         * on-disk from an older version of Android.
         */
        private boolean mIgnorePrepareStorageErrors;

        void setLastRequestQuietModeEnabledMillis(long millis) {
            mLastRequestQuietModeEnabledMillis = millis;
        }

        long getLastRequestQuietModeEnabledMillis() {
            return mLastRequestQuietModeEnabledMillis;
        }

        boolean getIgnorePrepareStorageErrors() {
            return mIgnorePrepareStorageErrors;
        }

        void setIgnorePrepareStorageErrors() {
            mIgnorePrepareStorageErrors = true;
        }

        void clearSeedAccountData() {
            seedAccountName = null;
            seedAccountType = null;
            seedAccountOptions = null;
            persistSeedData = false;
        }
    }

    @GuardedBy("mUsersLock")
    private final SparseArray<UserData> mUsers = new SparseArray<>();

    /**
     * Map of user type names to their corresponding {@link UserTypeDetails}.
     * Should not be modified after UserManagerService constructor finishes.
     */
    private final ArrayMap<String, UserTypeDetails> mUserTypes;

    /**
     * User restrictions set via UserManager.  This doesn't include restrictions set by
     * device owner / profile owners. Only non-empty restriction bundles are stored.
     *
     * DO NOT Change existing {@link Bundle} in it.  When changing a restriction for a user,
     * a new {@link Bundle} should always be created and set.  This is because a {@link Bundle}
     * maybe shared between {@link #mBaseUserRestrictions} and
     * {@link #mCachedEffectiveUserRestrictions}, but they should always updated separately.
     * (Otherwise we won't be able to detect what restrictions have changed in
     * {@link #updateUserRestrictionsInternalLR}.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mBaseUserRestrictions = new RestrictionsSet();

    /**
     * Cached user restrictions that are in effect -- i.e. {@link #mBaseUserRestrictions} combined
     * with device / profile owner restrictions.  We'll initialize it lazily; use
     * {@link #getEffectiveUserRestrictions} to access it.
     *
     * DO NOT Change existing {@link Bundle} in it.  When changing a restriction for a user,
     * a new {@link Bundle} should always be created and set.  This is because a {@link Bundle}
     * maybe shared between {@link #mBaseUserRestrictions} and
     * {@link #mCachedEffectiveUserRestrictions}, but they should always updated separately.
     * (Otherwise we won't be able to detect what restrictions have changed in
     * {@link #updateUserRestrictionsInternalLR}.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mCachedEffectiveUserRestrictions = new RestrictionsSet();

    /**
     * User restrictions that have already been applied in
     * {@link #updateUserRestrictionsInternalLR(Bundle, int)}.  We use it to detect restrictions
     * that have changed since the last
     * {@link #updateUserRestrictionsInternalLR(Bundle, int)} call.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mAppliedUserRestrictions = new RestrictionsSet();

    /**
     * User restrictions set by {@link com.android.server.devicepolicy.DevicePolicyManagerService}
     * that should be applied to all users, including guests. Only non-empty restriction bundles are
     * stored.
     * The key is the user id of the user whom the restriction originated from.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mDevicePolicyGlobalUserRestrictions = new RestrictionsSet();

    /**
     * Id of the user that set global restrictions.
     */
    @GuardedBy("mRestrictionsLock")
    private int mDeviceOwnerUserId = UserHandle.USER_NULL;

    /**
     * User restrictions set by {@link com.android.server.devicepolicy.DevicePolicyManagerService}
     * for each user.
     * The key is the user id of the user whom the restrictions are targeting.
     * The key inside the restrictionsSet is the user id of the user whom the restriction
     * originated from.
     * targetUserId -> originatingUserId -> restrictionBundle
     */
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<RestrictionsSet> mDevicePolicyLocalUserRestrictions =
            new SparseArray<>();

    @GuardedBy("mGuestRestrictions")
    private final Bundle mGuestRestrictions = new Bundle();

    /**
     * Set of user IDs being actively removed. Removed IDs linger in this set
     * for several seconds to work around a VFS caching issue.
     * Use {@link #addRemovingUserIdLocked(int)} to add elements to this array
     */
    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mRemovingUserIds = new SparseBooleanArray();

    /**
     * Queue of recently removed userIds. Used for recycling of userIds
     */
    @GuardedBy("mUsersLock")
    private final LinkedList<Integer> mRecentlyRemovedIds = new LinkedList<>();

    @GuardedBy("mUsersLock")
    private int[] mUserIds;

    @GuardedBy("mUsersLock")
    private int[] mUserIdsIncludingPreCreated;

    @GuardedBy("mPackagesLock")
    private int mNextSerialNumber;
    private int mUserVersion = 0;
    private int mUserTypeVersion = 0;

    private IAppOpsService mAppOpsService;

    private final LocalService mLocalService;

    @GuardedBy("mUsersLock")
    private boolean mIsDeviceManaged;

    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mIsUserManaged = new SparseBooleanArray();

    @GuardedBy("mUserRestrictionsListeners")
    private final ArrayList<UserRestrictionsListener> mUserRestrictionsListeners =
            new ArrayList<>();

    @GuardedBy("mUserRemovedListeners")
    private final ArrayList<UserLifecycleListener> mUserLifecycleListeners = new ArrayList<>();

    private final LockPatternUtils mLockPatternUtils;

    private final String ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK =
            "com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK";

    private final BroadcastReceiver mDisableQuietModeCallback = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK.equals(intent.getAction())) {
                return;
            }
            final IntentSender target = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_NULL);
            // Call setQuietModeEnabled on bg thread to avoid ANR
            BackgroundThread.getHandler().post(() ->
                    setQuietModeEnabled(userId, false, target, /* callingPackage */ null));
        }
    };

    /**
     * Cache the owner name string, since it could be read repeatedly on a critical code path
     * but hit by slow IO. This could be eliminated once we have the cached UserInfo in place.
     */
    private final AtomicReference<String> mOwnerName = new AtomicReference<>();

    private final TypedValue mOwnerNameTypedValue = new TypedValue();

    private final Configuration mLastConfiguration = new Configuration();

    private final BroadcastReceiver mConfigurationChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                return;
            }
            invalidateOwnerNameIfNecessary(context.getResources(), false /* forceUpdate */);
        }
    };

    // TODO(b/161915546): remove once userWithName() is fixed / removed
    // Use to debug / dump when user 0 is allocated at userWithName()
    public static final boolean DBG_ALLOCATION = false; // DO NOT SUBMIT WITH TRUE
    public final AtomicInteger mUser0Allocations;

    /**
     * Start an {@link IntentSender} when user is unlocked after disabling quiet mode.
     *
     * @see #requestQuietModeEnabled(String, boolean, int, IntentSender, int)
     */
    private class DisableQuietModeUserUnlockedCallback extends IProgressListener.Stub {
        private final IntentSender mTarget;

        public DisableQuietModeUserUnlockedCallback(IntentSender target) {
            Objects.requireNonNull(target);
            mTarget = target;
        }

        @Override
        public void onStarted(int id, Bundle extras) {}

        @Override
        public void onProgress(int id, int progress, Bundle extras) {}

        @Override
        public void onFinished(int id, Bundle extras) {
            mHandler.post(() -> {
                try {
                    mContext.startIntentSender(mTarget, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Slog.e(LOG_TAG, "Failed to start the target in the callback", e);
                }
            });
        }
    }

    /**
     * Whether all users should be created ephemeral.
     */
    @GuardedBy("mUsersLock")
    private boolean mForceEphemeralUsers;

    /**
     * The member mUserStates affects the return value of isUserUnlocked.
     * If any value in mUserStates changes, then the binder cache for
     * isUserUnlocked must be invalidated.  When adding mutating methods to
     * WatchedUserStates, be sure to invalidate the cache in the new
     * methods.
     */
    private class WatchedUserStates {
        final SparseIntArray states;
        public WatchedUserStates() {
            states = new SparseIntArray();
            invalidateIsUserUnlockedCache();
        }
        public int get(@UserIdInt int userId) {
            return states.get(userId);
        }
        public int get(@UserIdInt int userId, int fallback) {
            return states.indexOfKey(userId) >= 0 ? states.get(userId) : fallback;
        }
        public void put(@UserIdInt int userId, int state) {
            states.put(userId, state);
            invalidateIsUserUnlockedCache();
        }
        public void delete(@UserIdInt int userId) {
            states.delete(userId);
            invalidateIsUserUnlockedCache();
        }
        public boolean has(@UserIdInt int userId) {
            return states.get(userId, UserHandle.USER_NULL) != UserHandle.USER_NULL;
        }
        @Override
        public String toString() {
            return states.toString();
        }
        private void invalidateIsUserUnlockedCache() {
            UserManager.invalidateIsUserUnlockedCache();
        }
    }
    @GuardedBy("mUserStates")
    private final WatchedUserStates mUserStates = new WatchedUserStates();

    private static UserManagerService sInstance;

    public static UserManagerService getInstance() {
        synchronized (UserManagerService.class) {
            return sInstance;
        }
    }

    public static class LifeCycle extends SystemService {

        private UserManagerService mUms;

        /**
         * @param context
         */
        public LifeCycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mUms = UserManagerService.getInstance();
            publishBinderService(Context.USER_SERVICE, mUms);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mUms.cleanupPartialUsers();

                if (mUms.mPm.isDeviceUpgrading()) {
                    mUms.cleanupPreCreatedUsers();
                }
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser targetUser) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(targetUser.getUserIdentifier());
                if (user != null) {
                    user.startRealtime = SystemClock.elapsedRealtime();
                }
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser targetUser) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(targetUser.getUserIdentifier());
                if (user != null) {
                    user.unlockRealtime = SystemClock.elapsedRealtime();
                }
            }
        }

        @Override
        public void onUserStopping(@NonNull TargetUser targetUser) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(targetUser.getUserIdentifier());
                if (user != null) {
                    user.startRealtime = 0;
                    user.unlockRealtime = 0;
                }
            }
        }
    }

    // TODO b/28848102 Add support for test dependencies injection
    @VisibleForTesting
    UserManagerService(Context context) {
        this(context, null, null, new Object(), context.getCacheDir());
    }

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    UserManagerService(Context context, PackageManagerService pm, UserDataPreparer userDataPreparer,
            Object packagesLock) {
        this(context, pm, userDataPreparer, packagesLock, Environment.getDataDirectory());
    }

    private UserManagerService(Context context, PackageManagerService pm,
            UserDataPreparer userDataPreparer, Object packagesLock, File dataDir) {
        mContext = context;
        mPm = pm;
        mPackagesLock = packagesLock;
        mHandler = new MainHandler();
        mUserDataPreparer = userDataPreparer;
        mUserTypes = UserTypeFactory.getUserTypes();
        invalidateOwnerNameIfNecessary(context.getResources(), true /* forceUpdate */);
        synchronized (mPackagesLock) {
            mUsersDir = new File(dataDir, USER_INFO_DIR);
            mUsersDir.mkdirs();
            // Make zeroth user directory, for services to migrate their files to that location
            File userZeroDir = new File(mUsersDir, String.valueOf(UserHandle.USER_SYSTEM));
            userZeroDir.mkdirs();
            FileUtils.setPermissions(mUsersDir.toString(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                    -1, -1);
            mUserListFile = new File(mUsersDir, USER_LIST_FILENAME);
            initDefaultGuestRestrictions();
            readUserListLP();
            sInstance = this;
        }
        mSystemPackageInstaller = new UserSystemPackageInstaller(this, mUserTypes);
        mLocalService = new LocalService();
        LocalServices.addService(UserManagerInternal.class, mLocalService);
        mLockPatternUtils = new LockPatternUtils(mContext);
        mUserStates.put(UserHandle.USER_SYSTEM, UserState.STATE_BOOTING);
        mUser0Allocations = DBG_ALLOCATION ? new AtomicInteger() : null;
    }

    void systemReady() {
        mAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        synchronized (mRestrictionsLock) {
            applyUserRestrictionsLR(UserHandle.USER_SYSTEM);
        }

        mContext.registerReceiver(mDisableQuietModeCallback,
                new IntentFilter(ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK),
                null, mHandler);

        mContext.registerReceiver(mConfigurationChangeReceiver,
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
                null, mHandler);

        markEphemeralUsersForRemoval();
    }

    /**
     * This method retrieves the  {@link UserManagerInternal} only for the purpose of
     * PackageManagerService construction.
     */
    UserManagerInternal getInternalForInjectorOnly() {
        return mLocalService;
    }

    /** Marks all ephemeral users as slated for deletion. **/
    private void markEphemeralUsersForRemoval() {
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserInfo ui = mUsers.valueAt(i).info;
                if (ui.isEphemeral() && !ui.preCreated && ui.id != UserHandle.USER_SYSTEM) {
                    addRemovingUserIdLocked(ui.id);
                    ui.partial = true;
                    ui.flags |= UserInfo.FLAG_DISABLED;
                }
            }
        }
    }

    /* Prunes out any partially created or partially removed users. */
    void cleanupPartialUsers() {
        ArrayList<UserInfo> partials = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if ((ui.partial || ui.guestToRemove) && ui.id != UserHandle.USER_SYSTEM) {
                    partials.add(ui);
                    if (!mRemovingUserIds.get(ui.id)) {
                        addRemovingUserIdLocked(ui.id);
                    }
                    ui.partial = true;
                }
            }
        }
        final int partialsSize = partials.size();
        for (int i = 0; i < partialsSize; i++) {
            UserInfo ui = partials.get(i);
            Slog.w(LOG_TAG, "Removing partially created user " + ui.id
                    + " (name=" + ui.name + ")");
            removeUserState(ui.id);
        }
    }

    /**
     * Removes any pre-created users from the system. Should be invoked after OTAs, to ensure
     * pre-created users are not stale. New pre-created pool can be re-created after the update.
     */
    void cleanupPreCreatedUsers() {
        final ArrayList<UserInfo> preCreatedUsers;
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            preCreatedUsers = new ArrayList<>(userSize);
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.preCreated) {
                    preCreatedUsers.add(ui);
                    addRemovingUserIdLocked(ui.id);
                    ui.flags |= UserInfo.FLAG_DISABLED;
                    ui.partial = true;
                }
            }
        }
        final int preCreatedSize = preCreatedUsers.size();
        for (int i = 0; i < preCreatedSize; i++) {
            UserInfo ui = preCreatedUsers.get(i);
            Slog.i(LOG_TAG, "Removing pre-created user " + ui.id);
            removeUserState(ui.id);
        }
    }

    @Override
    public String getUserAccount(@UserIdInt int userId) {
        checkManageUserAndAcrossUsersFullPermission("get user account");
        synchronized (mUsersLock) {
            return mUsers.get(userId).account;
        }
    }

    @Override
    public void setUserAccount(@UserIdInt int userId, String accountName) {
        checkManageUserAndAcrossUsersFullPermission("set user account");
        UserData userToUpdate = null;
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                final UserData userData = mUsers.get(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "User not found for setting user account: u" + userId);
                    return;
                }
                String currentAccount = userData.account;
                if (!Objects.equals(currentAccount, accountName)) {
                    userData.account = accountName;
                    userToUpdate = userData;
                }
            }

            if (userToUpdate != null) {
                writeUserLP(userToUpdate);
            }
        }
    }

    @Override
    public UserInfo getPrimaryUser() {
        checkManageUsersPermission("query users");
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.isPrimary() && !mRemovingUserIds.get(ui.id)) {
                    return ui;
                }
            }
        }
        return null;
    }

    public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
        return getUsers(/*excludePartial= */ true, excludeDying, /* excludePreCreated= */
                true);
    }

    @Override
    public @NonNull List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying,
            boolean excludePreCreated) {
        checkManageOrCreateUsersPermission("query users");
        return getUsersInternal(excludePartial, excludeDying, excludePreCreated);
    }

    private @NonNull List<UserInfo> getUsersInternal(boolean excludePartial, boolean excludeDying,
            boolean excludePreCreated) {
        synchronized (mUsersLock) {
            ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if ((excludePartial && ui.partial)
                        || (excludeDying && mRemovingUserIds.get(ui.id))
                        || (excludePreCreated && ui.preCreated)) {
                    continue;
                }
                users.add(userWithName(ui));
            }
            return users;
        }
    }

    @Override
    public List<UserInfo> getProfiles(@UserIdInt int userId, boolean enabledOnly) {
        boolean returnFullInfo = true;
        if (userId != UserHandle.getCallingUserId()) {
            checkManageOrCreateUsersPermission("getting profiles related to user " + userId);
        } else {
            returnFullInfo = hasManageUsersPermission();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfilesLU(userId, /* userType */ null, enabledOnly, returnFullInfo);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // TODO(b/142482943): Will probably need a getProfiles(userType). But permissions may vary.

    @Override
    public int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly) {
        return getProfileIds(userId, null, enabledOnly);
    }

    // TODO(b/142482943): Probably @Override and make this accessible in UserManager.
    /**
     * Returns all the users of type userType that are in the same profile group as userId
     * (including userId itself, if it is of the appropriate user type).
     *
     * <p>If userType is non-{@code null}, only returns users that are of type userType.
     * If enabledOnly, only returns users that are not {@link UserInfo#FLAG_DISABLED}.
     */
    public int[] getProfileIds(@UserIdInt int userId, @Nullable String userType,
            boolean enabledOnly) {
        if (userId != UserHandle.getCallingUserId()) {
            checkManageOrCreateUsersPermission("getting profiles related to user " + userId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfileIdsLU(userId, userType, enabledOnly).toArray();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Assume permissions already checked and caller's identity cleared */
    @GuardedBy("mUsersLock")
    private List<UserInfo> getProfilesLU(@UserIdInt int userId, @Nullable String userType,
            boolean enabledOnly, boolean fullInfo) {
        IntArray profileIds = getProfileIdsLU(userId, userType, enabledOnly);
        ArrayList<UserInfo> users = new ArrayList<>(profileIds.size());
        for (int i = 0; i < profileIds.size(); i++) {
            int profileId = profileIds.get(i);
            UserInfo userInfo = mUsers.get(profileId).info;
            // If full info is not required - clear PII data to prevent 3P apps from reading it
            if (!fullInfo) {
                userInfo = new UserInfo(userInfo);
                userInfo.name = null;
                userInfo.iconPath = null;
            } else {
                userInfo = userWithName(userInfo);
            }
            users.add(userInfo);
        }
        return users;
    }

    /**
     *  Assume permissions already checked and caller's identity cleared
     *  <p>If userType is {@code null}, returns all profiles for user; else, only returns
     *  profiles of that type.
     */
    @GuardedBy("mUsersLock")
    private IntArray getProfileIdsLU(@UserIdInt int userId, @Nullable String userType,
            boolean enabledOnly) {
        UserInfo user = getUserInfoLU(userId);
        IntArray result = new IntArray(mUsers.size());
        if (user == null) {
            // Probably a dying user
            return result;
        }
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo profile = mUsers.valueAt(i).info;
            if (!isProfileOf(user, profile)) {
                continue;
            }
            if (enabledOnly && !profile.isEnabled()) {
                continue;
            }
            if (mRemovingUserIds.get(profile.id)) {
                continue;
            }
            if (profile.partial) {
                continue;
            }
            if (userType != null && !userType.equals(profile.userType)) {
                continue;
            }
            result.add(profile.id);
        }
        return result;
    }

    @Override
    public int getCredentialOwnerProfile(@UserIdInt int userId) {
        checkManageUsersPermission("get the credential owner");
        if (!mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
            synchronized (mUsersLock) {
                UserInfo profileParent = getProfileParentLU(userId);
                if (profileParent != null) {
                    return profileParent.id;
                }
            }
        }

        return userId;
    }

    @Override
    public boolean isSameProfileGroup(@UserIdInt int userId, int otherUserId) {
        if (userId == otherUserId) return true;
        checkManageUsersPermission("check if in the same profile group");
        return isSameProfileGroupNoChecks(userId, otherUserId);
    }

    private boolean isSameProfileGroupNoChecks(@UserIdInt int userId, int otherUserId) {
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || userInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                return false;
            }
            UserInfo otherUserInfo = getUserInfoLU(otherUserId);
            if (otherUserInfo == null
                    || otherUserInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                return false;
            }
            return userInfo.profileGroupId == otherUserInfo.profileGroupId;
        }
    }

    @Override
    public UserInfo getProfileParent(@UserIdInt int userId) {
        if (!hasManageUsersOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            throw new SecurityException(
                    "You need MANAGE_USERS or INTERACT_ACROSS_USERS permission to get the "
                            + "profile parent");
        }
        synchronized (mUsersLock) {
            return getProfileParentLU(userId);
        }
    }

    @Override
    public int getProfileParentId(@UserIdInt int userId) {
        checkManageUsersPermission("get the profile parent");
        return mLocalService.getProfileParentId(userId);
    }

    @GuardedBy("mUsersLock")
    private UserInfo getProfileParentLU(@UserIdInt int userId) {
        UserInfo profile = getUserInfoLU(userId);
        if (profile == null) {
            return null;
        }
        int parentUserId = profile.profileGroupId;
        if (parentUserId == userId || parentUserId == UserInfo.NO_PROFILE_GROUP_ID) {
            return null;
        } else {
            return getUserInfoLU(parentUserId);
        }
    }

    private static boolean isProfileOf(UserInfo user, UserInfo profile) {
        return user.id == profile.id ||
                (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && user.profileGroupId == profile.profileGroupId);
    }

    private void broadcastProfileAvailabilityChanges(UserHandle profileHandle,
            UserHandle parentHandle, boolean inQuietMode) {
        Intent intent = new Intent();
        if (inQuietMode) {
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        } else {
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        }
        intent.putExtra(Intent.EXTRA_QUIET_MODE, inQuietMode);
        intent.putExtra(Intent.EXTRA_USER, profileHandle);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, profileHandle.getIdentifier());
        getDevicePolicyManagerInternal().broadcastIntentToCrossProfileManifestReceiversAsUser(
                intent, parentHandle, /* requiresPermission= */ true);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, parentHandle);
    }

    @Override
    public boolean requestQuietModeEnabled(@NonNull String callingPackage, boolean enableQuietMode,
            @UserIdInt int userId, @Nullable IntentSender target, @QuietModeFlag int flags) {
        Objects.requireNonNull(callingPackage);

        if (enableQuietMode && target != null) {
            throw new IllegalArgumentException(
                    "target should only be specified when we are disabling quiet mode.");
        }

        final boolean dontAskCredential =
                (flags & UserManager.QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL) != 0;
        final boolean onlyIfCredentialNotRequired =
                (flags & UserManager.QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED) != 0;
        if (dontAskCredential && onlyIfCredentialNotRequired) {
            throw new IllegalArgumentException("invalid flags: " + flags);
        }

        ensureCanModifyQuietMode(
                callingPackage, Binder.getCallingUid(), userId, target != null, dontAskCredential);

        if (onlyIfCredentialNotRequired && callingPackage.equals(
                getPackageManagerInternal().getSystemUiServiceComponent().getPackageName())) {
            // This is to prevent SysUI from accidentally allowing the profile to turned on
            // without password when keyguard is still locked.
            throw new SecurityException("SystemUI is not allowed to set "
                    + "QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            if (enableQuietMode) {
                setQuietModeEnabled(
                        userId, true /* enableQuietMode */, target, callingPackage);
                return true;
            }
            if (mLockPatternUtils.isManagedProfileWithUnifiedChallenge(userId)) {
                KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
                // Normally only attempt to auto-unlock unified challenge if keyguard is not showing
                // (to stop turning profile on automatically via the QS tile), except when we
                // are called with QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED, in which
                // case always attempt to auto-unlock.
                if (!km.isDeviceLocked(mLocalService.getProfileParentId(userId))
                        || onlyIfCredentialNotRequired) {
                    mLockPatternUtils.tryUnlockWithCachedUnifiedChallenge(userId);
                }
            }
            final boolean needToShowConfirmCredential = !dontAskCredential
                    && mLockPatternUtils.isSecure(userId)
                    && !StorageManager.isUserKeyUnlocked(userId);
            if (needToShowConfirmCredential) {
                if (onlyIfCredentialNotRequired) {
                    return false;
                }
                showConfirmCredentialToDisableQuietMode(userId, target);
                return false;
            }
            setQuietModeEnabled(userId, false /* enableQuietMode */, target, callingPackage);
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * The caller can modify quiet mode if it meets one of these conditions:
     * <ul>
     *     <li>Has system UID or root UID</li>
     *     <li>Has {@link Manifest.permission#MODIFY_QUIET_MODE}</li>
     *     <li>Has {@link Manifest.permission#MANAGE_USERS}</li>
     *     <li>Is the foreground default launcher app</li>
     * </ul>
     * <p>
     * If caller wants to start an intent after disabling the quiet mode, or if it is targeting a
     * user in a different profile group from the caller, it must have
     * {@link Manifest.permission#MANAGE_USERS}.
     */
    private void ensureCanModifyQuietMode(String callingPackage, int callingUid,
            @UserIdInt int targetUserId, boolean startIntent, boolean dontAskCredential) {
        verifyCallingPackage(callingPackage, callingUid);

        if (hasManageUsersPermission()) {
            return;
        }
        if (startIntent) {
            throw new SecurityException("MANAGE_USERS permission is required to start intent "
                    + "after disabling quiet mode.");
        }
        if (dontAskCredential) {
            throw new SecurityException("MANAGE_USERS permission is required to disable quiet "
                    + "mode without credentials.");
        }
        if (!isSameProfileGroupNoChecks(UserHandle.getUserId(callingUid), targetUserId)) {
            throw new SecurityException("MANAGE_USERS permission is required to modify quiet mode "
                    + "for a different profile group.");
        }
        final boolean hasModifyQuietModePermission = hasPermissionGranted(
                Manifest.permission.MODIFY_QUIET_MODE, callingUid);
        if (hasModifyQuietModePermission) {
            return;
        }

        final ShortcutServiceInternal shortcutInternal =
                LocalServices.getService(ShortcutServiceInternal.class);
        if (shortcutInternal != null) {
            boolean isForegroundLauncher =
                    shortcutInternal.isForegroundDefaultLauncher(callingPackage, callingUid);
            if (isForegroundLauncher) {
                return;
            }
        }
        throw new SecurityException("Can't modify quiet mode, caller is neither foreground "
                + "default launcher nor has MANAGE_USERS/MODIFY_QUIET_MODE permission");
    }

    private void setQuietModeEnabled(@UserIdInt int userId, boolean enableQuietMode,
            IntentSender target, @Nullable String callingPackage) {
        final UserInfo profile, parent;
        final UserData profileUserData;
        synchronized (mUsersLock) {
            profile = getUserInfoLU(userId);
            parent = getProfileParentLU(userId);

            if (profile == null || !profile.isManagedProfile()) {
                throw new IllegalArgumentException("User " + userId + " is not a profile");
            }
            if (profile.isQuietModeEnabled() == enableQuietMode) {
                Slog.i(LOG_TAG, "Quiet mode is already " + enableQuietMode);
                return;
            }
            profile.flags ^= UserInfo.FLAG_QUIET_MODE;
            profileUserData = getUserDataLU(profile.id);
        }
        synchronized (mPackagesLock) {
            writeUserLP(profileUserData);
        }
        try {
            if (enableQuietMode) {
                ActivityManager.getService().stopUser(userId, /* force */true, null);
                LocalServices.getService(ActivityManagerInternal.class)
                        .killForegroundAppsForUser(userId);
            } else {
                IProgressListener callback = target != null
                        ? new DisableQuietModeUserUnlockedCallback(target)
                        : null;
                ActivityManager.getService().startUserInBackgroundWithListener(
                        userId, callback);
            }
            logQuietModeEnabled(userId, enableQuietMode, callingPackage);
        } catch (RemoteException e) {
            // Should not happen, same process.
            e.rethrowAsRuntimeException();
        }
        broadcastProfileAvailabilityChanges(profile.getUserHandle(), parent.getUserHandle(),
                enableQuietMode);
    }

    private void logQuietModeEnabled(@UserIdInt int userId, boolean enableQuietMode,
            @Nullable String callingPackage) {
        UserData userData;
        synchronized (mUsersLock) {
            userData = getUserDataLU(userId);
        }
        if (userData == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long period = (userData.getLastRequestQuietModeEnabledMillis() != 0L
                ? now - userData.getLastRequestQuietModeEnabledMillis()
                : now - userData.info.creationTime);
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.REQUEST_QUIET_MODE_ENABLED)
                .setStrings(callingPackage)
                .setBoolean(enableQuietMode)
                .setTimePeriod(period)
                .write();
        userData.setLastRequestQuietModeEnabledMillis(now);
    }

    @Override
    public boolean isQuietModeEnabled(@UserIdInt int userId) {
        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info == null || !info.isManagedProfile()) {
                return false;
            }
            return info.isQuietModeEnabled();
        }
    }

    /**
     * Show confirm credential screen to unlock user in order to turn off quiet mode.
     */
    private void showConfirmCredentialToDisableQuietMode(
            @UserIdInt int userId, @Nullable IntentSender target) {
        // otherwise, we show a profile challenge to trigger decryption of the user
        final KeyguardManager km = (KeyguardManager) mContext.getSystemService(
                Context.KEYGUARD_SERVICE);
        // We should use userId not credentialOwnerUserId here, as even if it is unified
        // lock, confirm screenlock page will know and show personal challenge, and unlock
        // work profile when personal challenge is correct
        final Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null, userId);
        if (unlockIntent == null) {
            return;
        }
        final Intent callBackIntent = new Intent(
                ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK);
        if (target != null) {
            callBackIntent.putExtra(Intent.EXTRA_INTENT, target);
        }
        callBackIntent.putExtra(Intent.EXTRA_USER_ID, userId);
        callBackIntent.setPackage(mContext.getPackageName());
        callBackIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                callBackIntent,
                PendingIntent.FLAG_CANCEL_CURRENT |
                        PendingIntent.FLAG_ONE_SHOT |
                        PendingIntent.FLAG_IMMUTABLE);
        // After unlocking the challenge, it will disable quiet mode and run the original
        // intentSender
        unlockIntent.putExtra(Intent.EXTRA_INTENT, pendingIntent.getIntentSender());
        unlockIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        mContext.startActivity(unlockIntent);
    }

    @Override
    public void setUserEnabled(@UserIdInt int userId) {
        checkManageUsersPermission("enable user");
        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info != null && !info.isEnabled()) {
                info.flags ^= UserInfo.FLAG_DISABLED;
                writeUserLP(getUserDataLU(info.id));
            }
        }
    }

    @Override
    public void setUserAdmin(@UserIdInt int userId) {
        checkManageUserAndAcrossUsersFullPermission("set user admin");

        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info == null || info.isAdmin()) {
                // Exit if no user found with that id, or the user is already an Admin.
                return;
            }

            info.flags ^= UserInfo.FLAG_ADMIN;
            writeUserLP(getUserDataLU(info.id));
        }
    }

    /**
     * Evicts a user's CE key by stopping and restarting the user.
     *
     * The key is evicted automatically by the user controller when the user has stopped.
     */
    @Override
    public void evictCredentialEncryptionKey(@UserIdInt int userId) {
        checkManageUsersPermission("evict CE key");
        final IActivityManager am = ActivityManagerNative.getDefault();
        final long identity = Binder.clearCallingIdentity();
        try {
            am.restartUserInBackground(userId);
        } catch (RemoteException re) {
            throw re.rethrowAsRuntimeException();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns whether the given user (specified by userId) is of the given user type, such as
     * {@link UserManager#USER_TYPE_FULL_GUEST}.
     */
    @Override
    public boolean isUserOfType(@UserIdInt int userId, String userType) {
        checkManageUsersPermission("check user type");
        return userType != null && userType.equals(getUserTypeNoChecks(userId));
    }

    /**
     * Returns the user type of the given userId, or null if the user doesn't exist.
     * <p>No permissions checks are made (but userId checks may be made).
     */
    private @Nullable String getUserTypeNoChecks(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null ? userInfo.userType : null;
        }
    }

    /**
     * Returns the UserTypeDetails of the given userId's user type, or null if the no such user.
     * <p>No permissions checks are made (but userId checks may be made).
     */
    private @Nullable UserTypeDetails getUserTypeDetailsNoChecks(@UserIdInt int userId) {
        final String typeStr = getUserTypeNoChecks(userId);
        return typeStr != null ? mUserTypes.get(typeStr) : null;
    }

    /**
     * Returns the UserTypeDetails of the given userInfo's user type (or null for a null userInfo).
     */
    private @Nullable UserTypeDetails getUserTypeDetails(@Nullable UserInfo userInfo) {
        final String typeStr = userInfo != null ? userInfo.userType : null;
        return typeStr != null ? mUserTypes.get(typeStr) : null;
    }

    @Override
    public UserInfo getUserInfo(@UserIdInt int userId) {
        checkManageOrCreateUsersPermission("query user");
        synchronized (mUsersLock) {
            return userWithName(getUserInfoLU(userId));
        }
    }

    /**
     * Returns a UserInfo object with the name filled in, for Owner, or the original
     * if the name is already set.
     */
    private UserInfo userWithName(UserInfo orig) {
        if (orig != null && orig.name == null && orig.id == UserHandle.USER_SYSTEM) {
            if (DBG_ALLOCATION) {
                final int number = mUser0Allocations.incrementAndGet();
                Slog.w(LOG_TAG, "System user instantiated at least " + number + " times");
            }
            UserInfo withName = new UserInfo(orig);
            withName.name = getOwnerName();
            return withName;
        } else {
            return orig;
        }
    }

    /** Returns whether the given user type is one of the FULL user types. */
    boolean isUserTypeSubtypeOfFull(String userType) {
        UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && userTypeDetails.isFull();
    }

    /** Returns whether the given user type is one of the PROFILE user types. */
    boolean isUserTypeSubtypeOfProfile(String userType) {
        UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && userTypeDetails.isProfile();
    }

    /** Returns whether the given user type is one of the SYSTEM user types. */
    boolean isUserTypeSubtypeOfSystem(String userType) {
        UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && userTypeDetails.isSystem();
    }

    @Override
    public boolean hasBadge(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "hasBadge");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        return userTypeDetails != null && userTypeDetails.hasBadge();
    }

    @Override
    public @StringRes int getUserBadgeLabelResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeLabelResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge label for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        final int badgeIndex = userInfo.profileBadge;
        return userTypeDetails.getBadgeLabel(badgeIndex);
    }

    /**
     * @return the color (not the resource ID) to be used for the user's badge in light theme
     */
    @Override
    public @ColorRes int getUserBadgeColorResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeColorResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge dark color for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getBadgeColor(userInfo.profileBadge);
    }

    /**
     * @return the color (not the resource ID) to be used for the user's badge in dark theme
     */
    @Override
    public @ColorRes int getUserBadgeDarkColorResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeDarkColorResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge color for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getDarkThemeBadgeColor(userInfo.profileBadge);
    }

    @Override
    public @DrawableRes int getUserIconBadgeResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserIconBadgeResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested icon badge for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getIconBadge();
    }

    @Override
    public @DrawableRes int getUserBadgeResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserBadgeResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getBadgePlain();
    }

    @Override
    public @DrawableRes int getUserBadgeNoBackgroundResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeNoBackgroundResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge (no background) for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getBadgeNoBackground();
    }

    @Override
    public boolean isProfile(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isProfile");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isProfile();
        }
    }

    @Override
    public boolean isManagedProfile(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isManagedProfile");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isManagedProfile();
        }
    }

    @Override
    public boolean isCloneProfile(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isCloneProfile");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isCloneProfile();
        }
    }

    @Override
    public boolean isMediaSharedWithParent(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "isMediaSharedWithParent");
        synchronized (mUsersLock) {
            UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
            return userTypeDetails != null ? userTypeDetails.isProfile()
                    && userTypeDetails.isMediaSharedWithParent() : false;
        }
    }

    @Override
    public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "isUserUnlockingOrUnlocked");
        return mLocalService.isUserUnlockingOrUnlocked(userId);
    }

    @Override
    public boolean isUserUnlocked(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isUserUnlocked");
        return mLocalService.isUserUnlocked(userId);
    }

    @Override
    public boolean isUserRunning(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isUserRunning");
        return mLocalService.isUserRunning(userId);
    }

    @Override
    public boolean isUserForeground(@UserIdInt int userId) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId
                && !hasManageUsersOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            throw new SecurityException("Caller from user " + callingUserId + " needs MANAGE_USERS "
                    + "or INTERACT_ACROSS_USERS permission to check if another user (" + userId
                    + ") is running in the foreground");
        }

        int currentUser = Binder.withCleanCallingIdentity(() -> ActivityManager.getCurrentUser());
        // TODO(b/179163496): should return true for profile users of the current user as well
        return currentUser == userId;
    }

    @Override
    public String getUserName() {
        final int callingUid = Binder.getCallingUid();
        if (!hasManageOrCreateUsersPermission()
                && !hasPermissionGranted(
                        android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED, callingUid)) {
            throw new SecurityException("You need MANAGE_USERS or CREATE_USERS or "
                    + "GET_ACCOUNTS_PRIVILEGED permissions to: get user name");
        }
        final int userId = UserHandle.getUserId(callingUid);
        synchronized (mUsersLock) {
            UserInfo userInfo = userWithName(getUserInfoLU(userId));
            return userInfo == null ? "" : userInfo.name;
        }
    }

    @Override
    public long getUserStartRealtime() {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mUsersLock) {
            final UserData user = getUserDataLU(userId);
            if (user != null) {
                return user.startRealtime;
            }
            return 0;
        }
    }

    @Override
    public long getUserUnlockRealtime() {
        synchronized (mUsersLock) {
            final UserData user = getUserDataLU(UserHandle.getUserId(Binder.getCallingUid()));
            if (user != null) {
                return user.unlockRealtime;
            }
            return 0;
        }
    }

    private void checkManageOrInteractPermissionIfCallerInOtherProfileGroup(@UserIdInt int userId,
            String name) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId == userId || isSameProfileGroupNoChecks(callingUserId, userId) ||
                hasManageUsersPermission()) {
            return;
        }
        if (!hasPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS,
                Binder.getCallingUid())) {
            throw new SecurityException("You need INTERACT_ACROSS_USERS or MANAGE_USERS permission "
                    + "to: check " + name);
        }
    }

    @Override
    public boolean isDemoUser(@UserIdInt int userId) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to query if u=" + userId
                    + " is a demo user");
        }
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isDemo();
        }
    }

    @Override
    public boolean isPreCreated(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isPreCreated");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.preCreated;
        }
    }

    @Override
    public boolean isRestricted() {
        synchronized (mUsersLock) {
            return getUserInfoLU(UserHandle.getCallingUserId()).isRestricted();
        }
    }

    @Override
    public boolean canHaveRestrictedProfile(@UserIdInt int userId) {
        checkManageUsersPermission("canHaveRestrictedProfile");
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || !userInfo.canHaveProfile()) {
                return false;
            }
            if (!userInfo.isAdmin()) {
                return false;
            }
            // restricted profile can be created if there is no DO set and the admin user has no PO;
            return !mIsDeviceManaged && !mIsUserManaged.get(userId);
        }
    }

    @Override
    public boolean hasRestrictedProfiles() {
        checkManageUsersPermission("hasRestrictedProfiles");
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = mUsers.valueAt(i).info;
                if (callingUserId != profile.id
                        && profile.restrictedProfileParentId == callingUserId) {
                    return true;
                }
            }
            return false;
        }
    }

    /*
     * Should be locked on mUsers before calling this.
     */
    @GuardedBy("mUsersLock")
    private UserInfo getUserInfoLU(@UserIdInt int userId) {
        final UserData userData = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (userData != null && userData.info.partial && !mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, "getUserInfo: unknown user #" + userId);
            return null;
        }
        return userData != null ? userData.info : null;
    }

    @GuardedBy("mUsersLock")
    private UserData getUserDataLU(@UserIdInt int userId) {
        final UserData userData = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (userData != null && userData.info.partial && !mRemovingUserIds.get(userId)) {
            return null;
        }
        return userData;
    }

    /**
     * Obtains {@link #mUsersLock} and return UserInfo from mUsers.
     * <p>No permissions checking or any addition checks are made</p>
     */
    private UserInfo getUserInfoNoChecks(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserData userData = mUsers.get(userId);
            return userData != null ? userData.info : null;
        }
    }

    /**
     * Obtains {@link #mUsersLock} and return UserData from mUsers.
     * <p>No permissions checking or any addition checks are made</p>
     */
    private UserData getUserDataNoChecks(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            return mUsers.get(userId);
        }
    }

    /** Called by PackageManagerService */
    public boolean exists(@UserIdInt int userId) {
        return mLocalService.exists(userId);
    }

    @Override
    public void setUserName(@UserIdInt int userId, String name) {
        checkManageUsersPermission("rename users");
        boolean changed = false;
        synchronized (mPackagesLock) {
            UserData userData = getUserDataNoChecks(userId);
            if (userData == null || userData.info.partial) {
                Slog.w(LOG_TAG, "setUserName: unknown user #" + userId);
                return;
            }
            if (name != null && !name.equals(userData.info.name)) {
                userData.info.name = name;
                writeUserLP(userData);
                changed = true;
            }
        }
        if (changed) {
            final long ident = Binder.clearCallingIdentity();
            try {
                sendUserInfoChangedBroadcast(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void setUserIcon(@UserIdInt int userId, Bitmap bitmap) {
        try {
            checkManageUsersPermission("update users");
            enforceUserRestriction(UserManager.DISALLOW_SET_USER_ICON, userId,
                    "Cannot set user icon");
            mLocalService.setUserIcon(userId, bitmap);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }



    private void sendUserInfoChangedBroadcast(@UserIdInt int userId) {
        Intent changedIntent = new Intent(Intent.ACTION_USER_INFO_CHANGED);
        changedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        changedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changedIntent, UserHandle.ALL);
    }

    @Override
    public ParcelFileDescriptor getUserIcon(int targetUserId) {
        if (!hasManageUsersOrPermission(android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED)) {
            throw new SecurityException("You need MANAGE_USERS or GET_ACCOUNTS_PRIVILEGED "
                    + "permissions to: get user icon");
        }
        String iconPath;
        synchronized (mPackagesLock) {
            UserInfo targetUserInfo = getUserInfoNoChecks(targetUserId);
            if (targetUserInfo == null || targetUserInfo.partial) {
                Slog.w(LOG_TAG, "getUserIcon: unknown user #" + targetUserId);
                return null;
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final int callingGroupId = getUserInfoNoChecks(callingUserId).profileGroupId;
            final int targetGroupId = targetUserInfo.profileGroupId;
            final boolean sameGroup = (callingGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && callingGroupId == targetGroupId);
            if ((callingUserId != targetUserId) && !sameGroup) {
                checkManageUsersPermission("get the icon of a user who is not related");
            }

            if (targetUserInfo.iconPath == null) {
                return null;
            }
            iconPath = targetUserInfo.iconPath;
        }

        try {
            return ParcelFileDescriptor.open(
                    new File(iconPath), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            Slog.e(LOG_TAG, "Couldn't find icon file", e);
        }
        return null;
    }

    public void makeInitialized(@UserIdInt int userId) {
        if (DBG) Slog.d(LOG_TAG, "makeInitialized(" + userId + ")");
        checkManageUsersPermission("makeInitialized");
        boolean scheduleWriteUser = false;
        UserData userData;
        synchronized (mUsersLock) {
            userData = mUsers.get(userId);
            if (userData == null || userData.info.partial) {
                Slog.w(LOG_TAG, "makeInitialized: unknown user #" + userId);
                return;
            }
            if ((userData.info.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                userData.info.flags |= UserInfo.FLAG_INITIALIZED;
                scheduleWriteUser = true;
            }
        }
        if (scheduleWriteUser) {
            scheduleWriteUser(userData);
        }
    }

    /**
     * If default guest restrictions haven't been initialized yet, add the basic
     * restrictions.
     */
    private void initDefaultGuestRestrictions() {
        synchronized (mGuestRestrictions) {
            if (mGuestRestrictions.isEmpty()) {
                UserTypeDetails guestType = mUserTypes.get(UserManager.USER_TYPE_FULL_GUEST);
                if (guestType == null) {
                    Slog.wtf(LOG_TAG, "Can't set default guest restrictions: type doesn't exist.");
                    return;
                }
                guestType.addDefaultRestrictionsTo(mGuestRestrictions);
            }
        }
    }

    @Override
    public Bundle getDefaultGuestRestrictions() {
        checkManageUsersPermission("getDefaultGuestRestrictions");
        synchronized (mGuestRestrictions) {
            return new Bundle(mGuestRestrictions);
        }
    }

    @Override
    public void setDefaultGuestRestrictions(Bundle restrictions) {
        checkManageUsersPermission("setDefaultGuestRestrictions");
        synchronized (mGuestRestrictions) {
            mGuestRestrictions.clear();
            mGuestRestrictions.putAll(restrictions);
        }
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
    }

    /**
     * See {@link UserManagerInternal#setDevicePolicyUserRestrictions}
     */
    private void setDevicePolicyUserRestrictionsInner(@UserIdInt int originatingUserId,
            @NonNull Bundle global, @NonNull RestrictionsSet local,
            boolean isDeviceOwner) {
        boolean globalChanged, localChanged;
        List<Integer> updatedLocalTargetUserIds;
        synchronized (mRestrictionsLock) {
            // Update global and local restrictions if they were changed.
            globalChanged = mDevicePolicyGlobalUserRestrictions
                    .updateRestrictions(originatingUserId, global);
            updatedLocalTargetUserIds = getUpdatedTargetUserIdsFromLocalRestrictions(
                    originatingUserId, local);
            localChanged = updateLocalRestrictionsForTargetUsersLR(originatingUserId, local,
                    updatedLocalTargetUserIds);

            if (isDeviceOwner) {
                // Remember the global restriction owner userId to be able to make a distinction
                // in getUserRestrictionSource on who set local policies.
                mDeviceOwnerUserId = originatingUserId;
            } else {
                if (mDeviceOwnerUserId == originatingUserId) {
                    // When profile owner sets restrictions it passes null global bundle and we
                    // reset global restriction owner userId.
                    // This means this user used to have DO, but now the DO is gone and the user
                    // instead has PO.
                    mDeviceOwnerUserId = UserHandle.USER_NULL;
                }
            }
        }
        if (DBG) {
            Slog.d(LOG_TAG, "setDevicePolicyUserRestrictions: "
                    + " originatingUserId=" + originatingUserId
                    + " global=" + global + (globalChanged ? " (changed)" : "")
                    + " local=" + local + (localChanged ? " (changed)" : "")
            );
        }
        // Don't call them within the mRestrictionsLock.
        synchronized (mPackagesLock) {
            if (globalChanged || localChanged) {
                if (updatedLocalTargetUserIds.size() == 1
                        && updatedLocalTargetUserIds.contains(originatingUserId)) {
                    writeUserLP(getUserDataNoChecks(originatingUserId));
                } else {
                    if (globalChanged) {
                        writeUserLP(getUserDataNoChecks(originatingUserId));
                    }
                    if (localChanged) {
                        for (int targetUserId : updatedLocalTargetUserIds) {
                            writeAllTargetUsersLP(targetUserId);
                        }
                    }
                }
            }
        }

        synchronized (mRestrictionsLock) {
            if (globalChanged) {
                applyUserRestrictionsForAllUsersLR();
            } else if (localChanged) {
                for (int targetUserId : updatedLocalTargetUserIds) {
                    applyUserRestrictionsLR(targetUserId);
                }
            }
        }
    }

    /**
     * @return the list of updated target user ids in device policy local restrictions for a
     * given originating user id.
     */
    private List<Integer> getUpdatedTargetUserIdsFromLocalRestrictions(int originatingUserId,
            @NonNull RestrictionsSet local) {
        List<Integer> targetUserIds = new ArrayList<>();
        // Update all the target user ids from the local restrictions set
        for (int i = 0; i < local.size(); i++) {
            targetUserIds.add(local.keyAt(i));
        }
        // Update the target user id from device policy local restrictions if the local
        // restrictions set does not contain the target user id.
        for (int i = 0; i < mDevicePolicyLocalUserRestrictions.size(); i++) {
            int targetUserId = mDevicePolicyLocalUserRestrictions.keyAt(i);
            RestrictionsSet restrictionsSet = mDevicePolicyLocalUserRestrictions.valueAt(i);
            if (!local.containsKey(targetUserId)
                    && restrictionsSet.containsKey(originatingUserId)) {
                targetUserIds.add(targetUserId);
            }
        }
        return targetUserIds;
    }

    /**
     * Update restrictions for all target users in the restriction set. If a target user does not
     * exist in device policy local restrictions, remove the restrictions bundle for that target
     * user originating from the specified originating user.
     */
    private boolean updateLocalRestrictionsForTargetUsersLR(int originatingUserId,
            RestrictionsSet local, List<Integer> updatedTargetUserIds) {
        boolean changed = false;
        for (int targetUserId : updatedTargetUserIds) {
            Bundle restrictions = local.getRestrictions(targetUserId);
            if (restrictions == null) {
                restrictions = new Bundle();
            }
            if (getDevicePolicyLocalRestrictionsForTargetUserLR(targetUserId)
                    .updateRestrictions(originatingUserId, restrictions)) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * A new restriction set is created if a restriction set does not already exist for a given
     * target user.
     *
     * @return restrictions set for a given target user.
     */
    private @NonNull RestrictionsSet getDevicePolicyLocalRestrictionsForTargetUserLR(
            int targetUserId) {
        RestrictionsSet result = mDevicePolicyLocalUserRestrictions.get(targetUserId);
        if (result == null) {
            result = new RestrictionsSet();
            mDevicePolicyLocalUserRestrictions.put(targetUserId, result);
        }
        return result;
    }

    @GuardedBy("mRestrictionsLock")
    private Bundle computeEffectiveUserRestrictionsLR(@UserIdInt int userId) {
        final Bundle baseRestrictions =
                UserRestrictionsUtils.nonNull(mBaseUserRestrictions.getRestrictions(userId));
        final Bundle global = mDevicePolicyGlobalUserRestrictions.mergeAll();
        final RestrictionsSet local = getDevicePolicyLocalRestrictionsForTargetUserLR(userId);

        if (BundleUtils.isEmpty(global) && local.isEmpty()) {
            // Common case first.
            return baseRestrictions;
        }
        final Bundle effective = BundleUtils.clone(baseRestrictions);
        UserRestrictionsUtils.merge(effective, global);
        UserRestrictionsUtils.merge(effective, local.mergeAll());

        return effective;
    }

    @GuardedBy("mRestrictionsLock")
    private void invalidateEffectiveUserRestrictionsLR(@UserIdInt int userId) {
        if (DBG) {
            Slog.d(LOG_TAG, "invalidateEffectiveUserRestrictions userId=" + userId);
        }
        mCachedEffectiveUserRestrictions.remove(userId);
    }

    private Bundle getEffectiveUserRestrictions(@UserIdInt int userId) {
        synchronized (mRestrictionsLock) {
            Bundle restrictions = mCachedEffectiveUserRestrictions.getRestrictions(userId);
            if (restrictions == null) {
                restrictions = computeEffectiveUserRestrictionsLR(userId);
                mCachedEffectiveUserRestrictions.updateRestrictions(userId, restrictions);
            }
            return restrictions;
        }
    }

    /** @return a specific user restriction that's in effect currently. */
    @Override
    public boolean hasUserRestriction(String restrictionKey, @UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "hasUserRestriction");
        return mLocalService.hasUserRestriction(restrictionKey, userId);
    }

    /** @return if any user has the given restriction. */
    @Override
    public boolean hasUserRestrictionOnAnyUser(String restrictionKey) {
        if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            return false;
        }
        final List<UserInfo> users = getUsers(/* excludeDying= */ true);
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            Bundle restrictions = getEffectiveUserRestrictions(userId);
            if (restrictions != null && restrictions.getBoolean(restrictionKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSettingRestrictedForUser(String setting, @UserIdInt int userId,
            String value, int callingUid) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }
        return UserRestrictionsUtils.isSettingRestrictedForUser(mContext, setting, userId,
                value, callingUid);
    }

    @Override
    public void addUserRestrictionsListener(final IUserRestrictionsListener listener) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }

        // NOTE: unregistering not supported; only client is the settings provider,
        // which installs a single static permanent listener.  If that listener goes
        // bad it implies the whole system process is going to crash.
        mLocalService.addUserRestrictionsListener(
                (int userId, Bundle newRestrict, Bundle prevRestrict) -> {
                    try {
                        listener.onUserRestrictionsChanged(userId, newRestrict, prevRestrict);
                    } catch (RemoteException re) {
                        Slog.e("IUserRestrictionsListener",
                                "Unable to invoke listener: " + re.getMessage());
                    }
                });
    }

    /**
     * @hide
     *
     * Returns who set a user restriction on a user.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @param restrictionKey the string key representing the restriction
     * @param userId the id of the user for whom to retrieve the restrictions.
     * @return The source of user restriction. Any combination of
     *         {@link UserManager#RESTRICTION_NOT_SET},
     *         {@link UserManager#RESTRICTION_SOURCE_SYSTEM},
     *         {@link UserManager#RESTRICTION_SOURCE_DEVICE_OWNER}
     *         and {@link UserManager#RESTRICTION_SOURCE_PROFILE_OWNER}
     */
    @Override
    public int getUserRestrictionSource(String restrictionKey, @UserIdInt int userId) {
        List<EnforcingUser> enforcingUsers = getUserRestrictionSources(restrictionKey,  userId);
        // Get "bitwise or" of restriction sources for all enforcing users.
        int result = UserManager.RESTRICTION_NOT_SET;
        for (int i = enforcingUsers.size() - 1; i >= 0; i--) {
            result |= enforcingUsers.get(i).getUserRestrictionSource();
        }
        return result;
    }

    @Override
    public List<EnforcingUser> getUserRestrictionSources(
            String restrictionKey, @UserIdInt int userId) {
        checkManageUsersPermission("getUserRestrictionSource");

        // Shortcut for the most common case
        if (!hasUserRestriction(restrictionKey, userId)) {
            return Collections.emptyList();
        }

        final List<EnforcingUser> result = new ArrayList<>();

        // Check if it is base restriction.
        if (hasBaseUserRestriction(restrictionKey, userId)) {
            result.add(new EnforcingUser(
                    UserHandle.USER_NULL, UserManager.RESTRICTION_SOURCE_SYSTEM));
        }

        synchronized (mRestrictionsLock) {
            // Check if it is set as a local restriction.
            result.addAll(getDevicePolicyLocalRestrictionsForTargetUserLR(userId).getEnforcingUsers(
                    restrictionKey, mDeviceOwnerUserId));

            // Check if it is set as a global restriction.
            result.addAll(mDevicePolicyGlobalUserRestrictions.getEnforcingUsers(restrictionKey,
                    mDeviceOwnerUserId));
        }
        return result;
    }

    /**
     * @return UserRestrictions that are in effect currently.  This always returns a new
     * {@link Bundle}.
     */
    @Override
    public Bundle getUserRestrictions(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserRestrictions");
        return BundleUtils.clone(getEffectiveUserRestrictions(userId));
    }

    @Override
    public boolean hasBaseUserRestriction(String restrictionKey, @UserIdInt int userId) {
        checkManageOrCreateUsersPermission("hasBaseUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            return false;
        }
        synchronized (mRestrictionsLock) {
            Bundle bundle = mBaseUserRestrictions.getRestrictions(userId);
            return (bundle != null && bundle.getBoolean(restrictionKey, false));
        }
    }

    @Override
    public void setUserRestriction(String key, boolean value, @UserIdInt int userId) {
        checkManageUsersPermission("setUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            return;
        }
        synchronized (mRestrictionsLock) {
            // Note we can't modify Bundles stored in mBaseUserRestrictions directly, so create
            // a copy.
            final Bundle newRestrictions = BundleUtils.clone(
                    mBaseUserRestrictions.getRestrictions(userId));
            newRestrictions.putBoolean(key, value);

            updateUserRestrictionsInternalLR(newRestrictions, userId);
        }
    }

    /**
     * Optionally updating user restrictions, calculate the effective user restrictions and also
     * propagate to other services and system settings.
     *
     * @param newBaseRestrictions User restrictions to set.
     *      If null, will not update user restrictions and only does the propagation.
     * @param userId target user ID.
     */
    @GuardedBy("mRestrictionsLock")
    private void updateUserRestrictionsInternalLR(
            @Nullable Bundle newBaseRestrictions, @UserIdInt int userId) {
        final Bundle prevAppliedRestrictions = UserRestrictionsUtils.nonNull(
                mAppliedUserRestrictions.getRestrictions(userId));

        // Update base restrictions.
        if (newBaseRestrictions != null) {
            // If newBaseRestrictions == the current one, it's probably a bug.
            final Bundle prevBaseRestrictions = mBaseUserRestrictions.getRestrictions(userId);

            Preconditions.checkState(prevBaseRestrictions != newBaseRestrictions);
            Preconditions.checkState(mCachedEffectiveUserRestrictions.getRestrictions(userId)
                    != newBaseRestrictions);

            if (mBaseUserRestrictions.updateRestrictions(userId, newBaseRestrictions)) {
                scheduleWriteUser(getUserDataNoChecks(userId));
            }
        }

        final Bundle effective = computeEffectiveUserRestrictionsLR(userId);

        mCachedEffectiveUserRestrictions.updateRestrictions(userId, effective);

        // Apply the new restrictions.
        if (DBG) {
            debug("Applying user restrictions: userId=" + userId
                    + " new=" + effective + " prev=" + prevAppliedRestrictions);
        }

        if (mAppOpsService != null) { // We skip it until system-ready.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mAppOpsService.setUserRestrictions(effective, mUserRestriconToken, userId);
                    } catch (RemoteException e) {
                        Slog.w(LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
                    }
                }
            });
        }

        propagateUserRestrictionsLR(userId, effective, prevAppliedRestrictions);

        mAppliedUserRestrictions.updateRestrictions(userId, new Bundle(effective));
    }

    private void propagateUserRestrictionsLR(final int userId,
            Bundle newRestrictions, Bundle prevRestrictions) {
        // Note this method doesn't touch any state, meaning it doesn't require mRestrictionsLock
        // actually, but we still need some kind of synchronization otherwise we might end up
        // calling listeners out-of-order, thus "LR".

        if (UserRestrictionsUtils.areEqual(newRestrictions, prevRestrictions)) {
            return;
        }

        final Bundle newRestrictionsFinal = new Bundle(newRestrictions);
        final Bundle prevRestrictionsFinal = new Bundle(prevRestrictions);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                UserRestrictionsUtils.applyUserRestrictions(
                        mContext, userId, newRestrictionsFinal, prevRestrictionsFinal);

                final UserRestrictionsListener[] listeners;
                synchronized (mUserRestrictionsListeners) {
                    listeners = new UserRestrictionsListener[mUserRestrictionsListeners.size()];
                    mUserRestrictionsListeners.toArray(listeners);
                }
                for (int i = 0; i < listeners.length; i++) {
                    listeners[i].onUserRestrictionsChanged(userId,
                            newRestrictionsFinal, prevRestrictionsFinal);
                }

                final Intent broadcast = new Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mContext.sendBroadcastAsUser(broadcast, UserHandle.of(userId));
            }
        });
    }

    // Package private for the inner class.
    @GuardedBy("mRestrictionsLock")
    void applyUserRestrictionsLR(@UserIdInt int userId) {
        updateUserRestrictionsInternalLR(null, userId);
    }

    @GuardedBy("mRestrictionsLock")
    // Package private for the inner class.
    void applyUserRestrictionsForAllUsersLR() {
        if (DBG) {
            debug("applyUserRestrictionsForAllUsersLR");
        }
        // First, invalidate all cached values.
        mCachedEffectiveUserRestrictions.removeAllRestrictions();

        // We don't want to call into ActivityManagerService while taking a lock, so we'll call
        // it on a handler.
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                // Then get the list of running users.
                final int[] runningUsers;
                try {
                    runningUsers = ActivityManager.getService().getRunningUserIds();
                } catch (RemoteException e) {
                    Slog.w(LOG_TAG, "Unable to access ActivityManagerService");
                    return;
                }
                // Then re-calculate the effective restrictions and apply, only for running users.
                // It's okay if a new user has started after the getRunningUserIds() call,
                // because we'll do the same thing (re-calculate the restrictions and apply)
                // when we start a user.
                synchronized (mRestrictionsLock) {
                    for (int i = 0; i < runningUsers.length; i++) {
                        applyUserRestrictionsLR(runningUsers[i]);
                    }
                }
            }
        };
        mHandler.post(r);
    }

    /**
     * Check if we've hit the limit of how many users can be created.
     */
    private boolean isUserLimitReached() {
        int count;
        synchronized (mUsersLock) {
            count = getAliveUsersExcludingGuestsCountLU();
        }
        return count >= UserManager.getMaxSupportedUsers();
    }

    /**
     * Returns whether more users of the given type can be added (based on how many users of that
     * type already exist).
     *
     * <p>For checking whether more profiles can be added to a particular parent use
     * {@link #canAddMoreProfilesToUser}.
     */
    private boolean canAddMoreUsersOfType(UserTypeDetails userTypeDetails) {
        final int max = userTypeDetails.getMaxAllowed();
        if (max == UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
            return true; // Indicates that there is no max.
        }
        return getNumberOfUsersOfType(userTypeDetails.getName()) < max;
    }

    /**
     * Gets the number of users of the given user type.
     * Does not include users that are about to die.
     */
    private int getNumberOfUsersOfType(String userType) {
        int count = 0;
        synchronized (mUsersLock) {
            final int size = mUsers.size();
            for (int i = 0; i < size; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.userType.equals(userType)
                        && !user.guestToRemove
                        && !mRemovingUserIds.get(user.id)
                        && !user.preCreated) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public boolean canAddMoreManagedProfiles(@UserIdInt int userId, boolean allowedToRemoveOne) {
        return canAddMoreProfilesToUser(UserManager.USER_TYPE_PROFILE_MANAGED, userId,
                allowedToRemoveOne);
    }

    /** Returns whether more profiles of the given type can be added to the given parent userId. */
    @Override
    public boolean canAddMoreProfilesToUser(String userType, @UserIdInt int userId,
            boolean allowedToRemoveOne) {
        checkManageUsersPermission("check if more profiles can be added.");
        final UserTypeDetails type = mUserTypes.get(userType);
        if (type == null) {
            return false;
        }
        // Managed profiles have their own specific rules.
        final boolean isManagedProfile = type.isManagedProfile();
        if (isManagedProfile) {
            if (!mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_MANAGED_USERS)) {
                return false;
            }
        }
        synchronized (mUsersLock) {
            // Check if the parent exists and its type is even allowed to have a profile.
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || !userInfo.canHaveProfile()) {
                return false;
            }

            // Limit the number of profiles that can be created
            final int maxUsersOfType = getMaxUsersOfTypePerParent(type);
            if (maxUsersOfType != UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
                final int userTypeCount = getProfileIds(userId, userType, false).length;
                final int profilesRemovedCount = userTypeCount > 0 && allowedToRemoveOne ? 1 : 0;
                if (userTypeCount - profilesRemovedCount >= maxUsersOfType) {
                    return false;
                }
                // Allow creating a managed profile in the special case where there is only one user
                if (isManagedProfile) {
                    int usersCountAfterRemoving = getAliveUsersExcludingGuestsCountLU()
                            - profilesRemovedCount;
                    return usersCountAfterRemoving == 1
                            || usersCountAfterRemoving < UserManager.getMaxSupportedUsers();
                }
            }
        }
        return true;
    }

    @GuardedBy("mUsersLock")
    private int getAliveUsersExcludingGuestsCountLU() {
        int aliveUserCount = 0;
        final int totalUserCount = mUsers.size();
        // Skip over users being removed
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = mUsers.valueAt(i).info;
            if (!mRemovingUserIds.get(user.id) && !user.isGuest() && !user.preCreated) {
                aliveUserCount++;
            }
        }
        return aliveUserCount;
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} and
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL INTERACT_ACROSS_USERS_FULL}
     * permissions can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller does not have enough privilege.
     */
    private static final void checkManageUserAndAcrossUsersFullPermission(String message) {
        final int uid = Binder.getCallingUid();

        if (uid == Process.SYSTEM_UID || uid == 0) {
            // System UID or root's UID are granted privilege.
            return;
        }

        if (hasPermissionGranted(Manifest.permission.MANAGE_USERS, uid)
                && hasPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid)) {
            // Apps with both permissions are granted privilege.
            return;
        }

        throw new SecurityException(
                "You need MANAGE_USERS and INTERACT_ACROSS_USERS_FULL permission to: " + message);
    }

    private static boolean hasPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(
                permission, uid, /* owningUid = */-1, /* exported = */ true) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}
     * permission can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     * @see #hasManageUsersPermission()
     */
    private static final void checkManageUsersPermission(String message) {
        if (!hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS}
     * can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     * @see #hasManageOrCreateUsersPermission()
     */
    private static final void checkManageOrCreateUsersPermission(String message) {
        if (!hasManageOrCreateUsersPermission()) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or CREATE_USERS permission to: " + message);
        }
    }

    /**
     * Similar to {@link #checkManageOrCreateUsersPermission(String)} but when the caller is tries
     * to create user/profiles other than what is allowed for
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS} permission, then it will only
     * allow callers with {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} permission.
     */
    private static final void checkManageOrCreateUsersPermission(int creationFlags) {
        if ((creationFlags & ~ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION) == 0) {
            if (!hasManageOrCreateUsersPermission()) {
                throw new SecurityException("You either need MANAGE_USERS or CREATE_USERS "
                        + "permission to create an user with flags: " + creationFlags);
            }
        } else if (!hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to create an user "
                    + " with flags: " + creationFlags);
        }
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}.
     */
    private static final boolean hasManageUsersPermission() {
        final int callingUid = Binder.getCallingUid();
        return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || callingUid == Process.ROOT_UID
                || hasPermissionGranted(android.Manifest.permission.MANAGE_USERS, callingUid);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or the provided permission.
     */
    private static final boolean hasManageUsersOrPermission(String alternativePermission) {
        final int callingUid = Binder.getCallingUid();
        return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || callingUid == Process.ROOT_UID
                || hasPermissionGranted(android.Manifest.permission.MANAGE_USERS, callingUid)
                || hasPermissionGranted(alternativePermission, callingUid);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS}.
     */
    private static final boolean hasManageOrCreateUsersPermission() {
        return hasManageUsersOrPermission(android.Manifest.permission.CREATE_USERS);
    }

    /**
     * Enforces that only the system UID or root's UID (on any user) can make certain calls to the
     * UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static void checkSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (!UserHandle.isSameApp(uid, Process.SYSTEM_UID) && uid != Process.ROOT_UID) {
            throw new SecurityException("Only system may: " + message);
        }
    }

    private void writeBitmapLP(UserInfo info, Bitmap bitmap) {
        try {
            File dir = new File(mUsersDir, Integer.toString(info.id));
            File file = new File(dir, USER_PHOTO_FILENAME);
            File tmp = new File(dir, USER_PHOTO_FILENAME_TMP);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(
                        dir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            FileOutputStream os;
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, os = new FileOutputStream(tmp))
                    && tmp.renameTo(file) && SELinux.restorecon(file)) {
                info.iconPath = file.getAbsolutePath();
            }
            try {
                os.close();
            } catch (IOException ioe) {
                // What the ... !
            }
            tmp.delete();
        } catch (FileNotFoundException e) {
            Slog.w(LOG_TAG, "Error setting photo for user ", e);
        }
    }

    /**
     * Returns an array of user ids.
     *
     * <p>This array is cached here for quick access, so do not modify or cache it elsewhere.
     *
     * @return the array of user ids.
     */
    public @NonNull int[] getUserIds() {
        synchronized (mUsersLock) {
            return mUserIds;
        }
    }

    /**
     * Returns an array of user ids, including pre-created users.
     *
     * <p>This method should only used for the specific cases that need to handle pre-created users;
     * most callers should call {@link #getUserIds()} instead.
     *
     * <p>This array is cached here for quick access, so do not modify or
     * cache it elsewhere.
     *
     * @return the array of user ids.
     */
    public @NonNull int[] getUserIdsIncludingPreCreated() {
        synchronized (mUsersLock) {
            return mUserIdsIncludingPreCreated;
        }
    }

    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    private void readUserListLP() {
        if (!mUserListFile.exists()) {
            fallbackToSingleUserLP();
            return;
        }
        FileInputStream fis = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fis = userListFile.openRead();
            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Skip
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read user list");
                fallbackToSingleUserLP();
                return;
            }

            mNextSerialNumber = -1;
            if (parser.getName().equals(TAG_USERS)) {
                mNextSerialNumber =
                        parser.getAttributeInt(null, ATTR_NEXT_SERIAL_NO, mNextSerialNumber);
                mUserVersion =
                        parser.getAttributeInt(null, ATTR_USER_VERSION, mUserVersion);
                mUserTypeVersion =
                        parser.getAttributeInt(null, ATTR_USER_TYPE_VERSION, mUserTypeVersion);
            }

            // Pre-O global user restriction were stored as a single bundle (as opposed to per-user
            // currently), take care of it in case of upgrade.
            Bundle oldDevicePolicyGlobalUserRestrictions = null;

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_USER)) {
                        UserData userData = readUserLP(parser.getAttributeInt(null, ATTR_ID));

                        if (userData != null) {
                            synchronized (mUsersLock) {
                                mUsers.put(userData.info.id, userData);
                                if (mNextSerialNumber < 0
                                        || mNextSerialNumber <= userData.info.id) {
                                    mNextSerialNumber = userData.info.id + 1;
                                }
                            }
                        }
                    } else if (name.equals(TAG_GUEST_RESTRICTIONS)) {
                        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                                && type != XmlPullParser.END_TAG) {
                            if (type == XmlPullParser.START_TAG) {
                                if (parser.getName().equals(TAG_RESTRICTIONS)) {
                                    synchronized (mGuestRestrictions) {
                                        UserRestrictionsUtils
                                                .readRestrictions(parser, mGuestRestrictions);
                                    }
                                }
                                break;
                            }
                        }
                    } else if (name.equals(TAG_DEVICE_OWNER_USER_ID)
                            // Legacy name, should only be encountered when upgrading from pre-O.
                            || name.equals(TAG_GLOBAL_RESTRICTION_OWNER_ID)) {
                        mDeviceOwnerUserId =
                                parser.getAttributeInt(null, ATTR_ID, mDeviceOwnerUserId);
                    } else if (name.equals(TAG_DEVICE_POLICY_RESTRICTIONS)) {
                        // Should only happen when upgrading from pre-O (version < 7).
                        oldDevicePolicyGlobalUserRestrictions =
                                UserRestrictionsUtils.readRestrictions(parser);
                    }
                }
            }

            updateUserIds();
            upgradeIfNecessaryLP(oldDevicePolicyGlobalUserRestrictions);
        } catch (IOException | XmlPullParserException e) {
            fallbackToSingleUserLP();
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    /**
     * Upgrade steps between versions, either for fixing bugs or changing the data format.
     * @param oldGlobalUserRestrictions Pre-O global device policy restrictions.
     */
    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    private void upgradeIfNecessaryLP(Bundle oldGlobalUserRestrictions) {
        upgradeIfNecessaryLP(oldGlobalUserRestrictions, mUserVersion, mUserTypeVersion);
    }

    /**
     * Version of {@link #upgradeIfNecessaryLP(Bundle)} that takes in the userVersion for testing
     * purposes. For non-tests, use {@link #upgradeIfNecessaryLP(Bundle)}.
     */
    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    @VisibleForTesting
    void upgradeIfNecessaryLP(Bundle oldGlobalUserRestrictions, int userVersion,
            int userTypeVersion) {
        Set<Integer> userIdsToWrite = new ArraySet<>();
        final int originalVersion = mUserVersion;
        final int originalUserTypeVersion = mUserTypeVersion;
        if (userVersion < 1) {
            // Assign a proper name for the owner, if not initialized correctly before
            UserData userData = getUserDataNoChecks(UserHandle.USER_SYSTEM);
            if ("Primary".equals(userData.info.name)) {
                userData.info.name =
                        mContext.getResources().getString(com.android.internal.R.string.owner_name);
                userIdsToWrite.add(userData.info.id);
            }
            userVersion = 1;
        }

        if (userVersion < 2) {
            // Owner should be marked as initialized
            UserData userData = getUserDataNoChecks(UserHandle.USER_SYSTEM);
            if ((userData.info.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                userData.info.flags |= UserInfo.FLAG_INITIALIZED;
                userIdsToWrite.add(userData.info.id);
            }
            userVersion = 2;
        }


        if (userVersion < 4) {
            userVersion = 4;
        }

        if (userVersion < 5) {
            initDefaultGuestRestrictions();
            userVersion = 5;
        }

        if (userVersion < 6) {
            final boolean splitSystemUser = UserManager.isSplitSystemUser();
            synchronized (mUsersLock) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    // In non-split mode, only user 0 can have restricted profiles
                    if (!splitSystemUser && userData.info.isRestricted()
                            && (userData.info.restrictedProfileParentId
                                    == UserInfo.NO_PROFILE_GROUP_ID)) {
                        userData.info.restrictedProfileParentId = UserHandle.USER_SYSTEM;
                        userIdsToWrite.add(userData.info.id);
                    }
                }
            }
            userVersion = 6;
        }

        if (userVersion < 7) {
            // Previously only one user could enforce global restrictions, now it is per-user.
            synchronized (mRestrictionsLock) {
                if (!BundleUtils.isEmpty(oldGlobalUserRestrictions)
                        && mDeviceOwnerUserId != UserHandle.USER_NULL) {
                    mDevicePolicyGlobalUserRestrictions.updateRestrictions(
                            mDeviceOwnerUserId, oldGlobalUserRestrictions);
                }
                // ENSURE_VERIFY_APPS is now enforced globally even if put by profile owner, so move
                // it from local to global bundle for all users who set it.
                UserRestrictionsUtils.moveRestriction(UserManager.ENSURE_VERIFY_APPS,
                        mDevicePolicyLocalUserRestrictions, mDevicePolicyGlobalUserRestrictions
                );
            }
            // DISALLOW_CONFIG_WIFI was made a default guest restriction some time during version 6.
            final UserInfo currentGuestUser = findCurrentGuestUser();
            if (currentGuestUser != null && !hasUserRestriction(
                    UserManager.DISALLOW_CONFIG_WIFI, currentGuestUser.id)) {
                setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, currentGuestUser.id);
            }
            userVersion = 7;
        }

        if (userVersion < 8) {
            // Added FLAG_FULL and FLAG_SYSTEM flags.
            synchronized (mUsersLock) {
                UserData userData = mUsers.get(UserHandle.USER_SYSTEM);
                userData.info.flags |= UserInfo.FLAG_SYSTEM;
                if (!UserManager.isHeadlessSystemUserMode()) {
                    userData.info.flags |= UserInfo.FLAG_FULL;
                }
                userIdsToWrite.add(userData.info.id);

                // Mark FULL all non-profile users except USER_SYSTEM.
                // Start index at 1 since USER_SYSTEM is the smallest userId and we're skipping it.
                for (int i = 1; i < mUsers.size(); i++) {
                    userData = mUsers.valueAt(i);
                    if ((userData.info.flags & UserInfo.FLAG_MANAGED_PROFILE) == 0) {
                        userData.info.flags |= UserInfo.FLAG_FULL;
                        userIdsToWrite.add(userData.info.id);
                    }
                }
            }
            userVersion = 8;
        }

        if (userVersion < 9) {
            // Convert from UserInfo flags to UserTypes. Apply FLAG_PROFILE to FLAG_MANAGED_PROFILE.
            synchronized (mUsersLock) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    final int flags = userData.info.flags;
                    if ((flags & UserInfo.FLAG_SYSTEM) != 0) {
                        if ((flags & UserInfo.FLAG_FULL) != 0) {
                            userData.info.userType = UserManager.USER_TYPE_FULL_SYSTEM;
                        } else {
                            userData.info.userType = UserManager.USER_TYPE_SYSTEM_HEADLESS;
                        }
                    } else {
                        try {
                            userData.info.userType = UserInfo.getDefaultUserType(flags);
                        } catch (IllegalArgumentException e) {
                            // TODO(b/142482943): What should we do here? Delete user? Crashloop?
                            throw new IllegalStateException("Cannot upgrade user with flags "
                                    + Integer.toHexString(flags) + " because it doesn't correspond "
                                    + "to a valid user type.", e);
                        }
                    }
                    // OEMs are responsible for their own custom upgrade logic here.

                    final UserTypeDetails userTypeDetails = mUserTypes.get(userData.info.userType);
                    if (userTypeDetails == null) {
                        throw new IllegalStateException(
                                "Cannot upgrade user with flags " + Integer.toHexString(flags)
                                        + " because " + userData.info.userType + " isn't defined"
                                        + " on this device!");
                    }
                    userData.info.flags |= userTypeDetails.getDefaultUserInfoFlags();
                    userIdsToWrite.add(userData.info.id);
                }
            }
            userVersion = 9;
        }

        // Done with userVersion changes, moving on to deal with userTypeVersion upgrades
        // Upgrade from previous user type to a new user type
        final int newUserTypeVersion = UserTypeFactory.getUserTypeVersion();
        if (newUserTypeVersion > userTypeVersion) {
            synchronized (mUsersLock) {
                upgradeUserTypesLU(UserTypeFactory.getUserTypeUpgrades(), mUserTypes,
                        userTypeVersion, userIdsToWrite);
            }
        }

        if (userVersion < USER_VERSION) {
            Slog.w(LOG_TAG, "User version " + mUserVersion + " didn't upgrade as expected to "
                    + USER_VERSION);
        } else {
            mUserVersion = userVersion;
            mUserTypeVersion = newUserTypeVersion;

            if (originalVersion < mUserVersion || originalUserTypeVersion < mUserTypeVersion) {
                for (int userId : userIdsToWrite) {
                    UserData userData = getUserDataNoChecks(userId);
                    if (userData != null) {
                        writeUserLP(userData);
                    }
                }
                writeUserListLP();
            }
        }
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
    void upgradeUserTypesLU(@NonNull List<UserTypeFactory.UserTypeUpgrade> upgradeOps,
            @NonNull ArrayMap<String, UserTypeDetails> userTypes,
            final int formerUserTypeVersion,
            @NonNull Set<Integer> userIdsToWrite) {
        for (UserTypeFactory.UserTypeUpgrade userTypeUpgrade : upgradeOps) {
            if (DBG) {
                Slog.i(LOG_TAG, "Upgrade: " + userTypeUpgrade.getFromType() + " to: "
                        + userTypeUpgrade.getToType() + " maxVersion: "
                        + userTypeUpgrade.getUpToVersion());
            }

            // upgrade user type if version up to getUpToVersion()
            if (formerUserTypeVersion <= userTypeUpgrade.getUpToVersion()) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    if (userTypeUpgrade.getFromType().equals(userData.info.userType)) {
                        final UserTypeDetails newUserType = userTypes.get(
                                userTypeUpgrade.getToType());

                        if (newUserType == null) {
                            throw new IllegalStateException(
                                    "Upgrade destination user type not defined: "
                                            + userTypeUpgrade.getToType());
                        }

                        upgradeProfileToTypeLU(userData.info, newUserType);
                        userIdsToWrite.add(userData.info.id);
                    }
                }
            }
        }
    }

    /**
     * Changes the user type of a profile to a new user type.
     * @param userInfo    The user to be updated.
     * @param newUserType The new user type.
     */
    @GuardedBy("mUsersLock")
    @VisibleForTesting
    void upgradeProfileToTypeLU(@NonNull UserInfo userInfo, @NonNull UserTypeDetails newUserType) {
        Slog.i(LOG_TAG, "Upgrading user " + userInfo.id
                + " from " + userInfo.userType
                + " to " + newUserType.getName());

        if (!userInfo.isProfile()) {
            throw new IllegalStateException(
                    "Can only upgrade profile types. " + userInfo.userType
                            + " is not a profile type.");
        }

        // Exceeded maximum profiles for parent user: log error, but allow upgrade
        if (!canAddMoreProfilesToUser(newUserType.getName(), userInfo.profileGroupId, false)) {
            Slog.w(LOG_TAG,
                    "Exceeded maximum profiles of type " + newUserType.getName() + " for user "
                            + userInfo.id + ". Maximum allowed= "
                            + newUserType.getMaxAllowedPerParent());
        }

        final UserTypeDetails oldUserType = mUserTypes.get(userInfo.userType);
        final int oldFlags;
        if (oldUserType != null) {
            oldFlags = oldUserType.getDefaultUserInfoFlags();
        } else {
            // if oldUserType is missing from config_user_types.xml -> can only assume FLAG_PROFILE
            oldFlags = UserInfo.FLAG_PROFILE;
        }

        //convert userData to newUserType
        userInfo.userType = newUserType.getName();
        // remove old default flags and add newUserType's default flags
        userInfo.flags = newUserType.getDefaultUserInfoFlags() | (userInfo.flags ^ oldFlags);

        // merge existing base restrictions with the new type's default restrictions
        synchronized (mRestrictionsLock) {
            if (!BundleUtils.isEmpty(newUserType.getDefaultRestrictions())) {
                final Bundle newRestrictions = BundleUtils.clone(
                        mBaseUserRestrictions.getRestrictions(userInfo.id));
                UserRestrictionsUtils.merge(newRestrictions,
                        newUserType.getDefaultRestrictions());
                updateUserRestrictionsInternalLR(newRestrictions, userInfo.id);
                if (DBG) {
                    Slog.i(LOG_TAG, "Updated user " + userInfo.id
                            + " restrictions to " + newRestrictions);
                }
            }
        }

        // re-compute badge index
        userInfo.profileBadge = getFreeProfileBadgeLU(userInfo.profileGroupId, userInfo.userType);
    }

    @GuardedBy({"mPackagesLock", "mRestrictionsLock"})
    private void fallbackToSingleUserLP() {
        int flags = UserInfo.FLAG_SYSTEM | UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_ADMIN
                | UserInfo.FLAG_PRIMARY;
        // Create the system user
        String systemUserType = UserManager.isHeadlessSystemUserMode() ?
                UserManager.USER_TYPE_SYSTEM_HEADLESS : UserManager.USER_TYPE_FULL_SYSTEM;
        flags |= mUserTypes.get(systemUserType).getDefaultUserInfoFlags();
        UserInfo system = new UserInfo(UserHandle.USER_SYSTEM, null, null, flags, systemUserType);
        UserData userData = putUserInfo(system);
        mNextSerialNumber = MIN_USER_ID;
        mUserVersion = USER_VERSION;
        mUserTypeVersion = UserTypeFactory.getUserTypeVersion();

        Bundle restrictions = new Bundle();
        try {
            final String[] defaultFirstUserRestrictions = mContext.getResources().getStringArray(
                    com.android.internal.R.array.config_defaultFirstUserRestrictions);
            for (String userRestriction : defaultFirstUserRestrictions) {
                if (UserRestrictionsUtils.isValidRestriction(userRestriction)) {
                    restrictions.putBoolean(userRestriction, true);
                }
            }
        } catch (Resources.NotFoundException e) {
            Slog.e(LOG_TAG, "Couldn't find resource: config_defaultFirstUserRestrictions", e);
        }

        if (!restrictions.isEmpty()) {
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.updateRestrictions(UserHandle.USER_SYSTEM,
                        restrictions);
            }
        }

        updateUserIds();
        initDefaultGuestRestrictions();

        writeUserLP(userData);
        writeUserListLP();
    }

    private String getOwnerName() {
        return mOwnerName.get();
    }

    private void invalidateOwnerNameIfNecessary(@NonNull Resources res, boolean forceUpdate) {
        final int configChanges = mLastConfiguration.updateFrom(res.getConfiguration());
        if (forceUpdate || (configChanges & mOwnerNameTypedValue.changingConfigurations) != 0) {
            res.getValue(com.android.internal.R.string.owner_name, mOwnerNameTypedValue, true);
            final CharSequence ownerName = mOwnerNameTypedValue.coerceToString();
            mOwnerName.set(ownerName != null ? ownerName.toString() : null);
        }
    }

    private void scheduleWriteUser(UserData userData) {
        if (DBG) {
            debug("scheduleWriteUser");
        }
        // No need to wrap it within a lock -- worst case, we'll just post the same message
        // twice.
        if (!mHandler.hasMessages(WRITE_USER_MSG, userData)) {
            Message msg = mHandler.obtainMessage(WRITE_USER_MSG, userData);
            mHandler.sendMessageDelayed(msg, WRITE_USER_DELAY);
        }
    }

    private void writeAllTargetUsersLP(int originatingUserId) {
        for (int i = 0; i < mDevicePolicyLocalUserRestrictions.size(); i++) {
            int targetUserId = mDevicePolicyLocalUserRestrictions.keyAt(i);
            RestrictionsSet restrictionsSet = mDevicePolicyLocalUserRestrictions.valueAt(i);
            if (restrictionsSet.containsKey(originatingUserId)) {
                writeUserLP(getUserDataNoChecks(targetUserId));
            }
        }
    }

    private void writeUserLP(UserData userData) {
        if (DBG) {
            debug("writeUserLP " + userData);
        }
        FileOutputStream fos = null;
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userData.info.id + XML_SUFFIX));
        try {
            fos = userFile.startWrite();
            writeUserLP(userData, fos);
            userFile.finishWrite(fos);
        } catch (Exception ioe) {
            Slog.e(LOG_TAG, "Error writing user info " + userData.info.id, ioe);
            userFile.failWrite(fos);
        }
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    @VisibleForTesting
    void writeUserLP(UserData userData, OutputStream os)
            throws IOException, XmlPullParserException {
        final TypedXmlSerializer serializer = Xml.resolveSerializer(os);
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        final UserInfo userInfo = userData.info;
        serializer.startTag(null, TAG_USER);
        serializer.attributeInt(null, ATTR_ID, userInfo.id);
        serializer.attributeInt(null, ATTR_SERIAL_NO, userInfo.serialNumber);
        serializer.attributeInt(null, ATTR_FLAGS, userInfo.flags);
        serializer.attribute(null, ATTR_TYPE, userInfo.userType);
        serializer.attributeLong(null, ATTR_CREATION_TIME, userInfo.creationTime);
        serializer.attributeLong(null, ATTR_LAST_LOGGED_IN_TIME, userInfo.lastLoggedInTime);
        if (userInfo.lastLoggedInFingerprint != null) {
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_FINGERPRINT,
                    userInfo.lastLoggedInFingerprint);
        }
        if (userInfo.iconPath != null) {
            serializer.attribute(null,  ATTR_ICON_PATH, userInfo.iconPath);
        }
        if (userInfo.partial) {
            serializer.attributeBoolean(null, ATTR_PARTIAL, true);
        }
        if (userInfo.preCreated) {
            serializer.attributeBoolean(null, ATTR_PRE_CREATED, true);
        }
        if (userInfo.convertedFromPreCreated) {
            serializer.attributeBoolean(null, ATTR_CONVERTED_FROM_PRE_CREATED, true);
        }
        if (userInfo.guestToRemove) {
            serializer.attributeBoolean(null, ATTR_GUEST_TO_REMOVE, true);
        }
        if (userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
            serializer.attributeInt(null, ATTR_PROFILE_GROUP_ID, userInfo.profileGroupId);
        }
        serializer.attributeInt(null, ATTR_PROFILE_BADGE, userInfo.profileBadge);
        if (userInfo.restrictedProfileParentId != UserInfo.NO_PROFILE_GROUP_ID) {
            serializer.attributeInt(null, ATTR_RESTRICTED_PROFILE_PARENT_ID,
                    userInfo.restrictedProfileParentId);
        }
        // Write seed data
        if (userData.persistSeedData) {
            if (userData.seedAccountName != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_NAME, userData.seedAccountName);
            }
            if (userData.seedAccountType != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_TYPE, userData.seedAccountType);
            }
        }
        if (userInfo.name != null) {
            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);
        }
        synchronized (mRestrictionsLock) {
            UserRestrictionsUtils.writeRestrictions(serializer,
                    mBaseUserRestrictions.getRestrictions(userInfo.id), TAG_RESTRICTIONS);
            getDevicePolicyLocalRestrictionsForTargetUserLR(userInfo.id).writeRestrictions(
                    serializer, TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS);
            UserRestrictionsUtils.writeRestrictions(serializer,
                    mDevicePolicyGlobalUserRestrictions.getRestrictions(userInfo.id),
                    TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS);
        }

        if (userData.account != null) {
            serializer.startTag(null, TAG_ACCOUNT);
            serializer.text(userData.account);
            serializer.endTag(null, TAG_ACCOUNT);
        }

        if (userData.persistSeedData && userData.seedAccountOptions != null) {
            serializer.startTag(null, TAG_SEED_ACCOUNT_OPTIONS);
            userData.seedAccountOptions.saveToXml(serializer);
            serializer.endTag(null, TAG_SEED_ACCOUNT_OPTIONS);
        }

        if (userData.getLastRequestQuietModeEnabledMillis() != 0L) {
            serializer.startTag(/* namespace */ null, TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL);
            serializer.text(String.valueOf(userData.getLastRequestQuietModeEnabledMillis()));
            serializer.endTag(/* namespace */ null, TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL);
        }

        serializer.startTag(/* namespace */ null, TAG_IGNORE_PREPARE_STORAGE_ERRORS);
        serializer.text(String.valueOf(userData.getIgnorePrepareStorageErrors()));
        serializer.endTag(/* namespace */ null, TAG_IGNORE_PREPARE_STORAGE_ERRORS);

        serializer.endTag(null, TAG_USER);

        serializer.endDocument();
    }

    /*
     * Writes the user list file in this format:
     *
     * <users nextSerialNumber="3">
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    private void writeUserListLP() {
        if (DBG) {
            debug("writeUserList");
        }
        FileOutputStream fos = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fos = userListFile.startWrite();
            final TypedXmlSerializer serializer = Xml.resolveSerializer(fos);
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USERS);
            serializer.attributeInt(null, ATTR_NEXT_SERIAL_NO, mNextSerialNumber);
            serializer.attributeInt(null, ATTR_USER_VERSION, mUserVersion);
            serializer.attributeInt(null, ATTR_USER_TYPE_VERSION, mUserTypeVersion);

            serializer.startTag(null, TAG_GUEST_RESTRICTIONS);
            synchronized (mGuestRestrictions) {
                UserRestrictionsUtils
                        .writeRestrictions(serializer, mGuestRestrictions, TAG_RESTRICTIONS);
            }
            serializer.endTag(null, TAG_GUEST_RESTRICTIONS);
            serializer.startTag(null, TAG_DEVICE_OWNER_USER_ID);
            serializer.attributeInt(null, ATTR_ID, mDeviceOwnerUserId);
            serializer.endTag(null, TAG_DEVICE_OWNER_USER_ID);
            int[] userIdsToWrite;
            synchronized (mUsersLock) {
                userIdsToWrite = new int[mUsers.size()];
                for (int i = 0; i < userIdsToWrite.length; i++) {
                    UserInfo user = mUsers.valueAt(i).info;
                    userIdsToWrite[i] = user.id;
                }
            }
            for (int id : userIdsToWrite) {
                serializer.startTag(null, TAG_USER);
                serializer.attributeInt(null, ATTR_ID, id);
                serializer.endTag(null, TAG_USER);
            }

            serializer.endTag(null, TAG_USERS);

            serializer.endDocument();
            userListFile.finishWrite(fos);
        } catch (Exception e) {
            userListFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing user list");
        }
    }

    private UserData readUserLP(int id) {
        FileInputStream fis = null;
        try {
            AtomicFile userFile =
                    new AtomicFile(new File(mUsersDir, Integer.toString(id) + XML_SUFFIX));
            fis = userFile.openRead();
            return readUserLP(id, fis);
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Error reading user list");
        } catch (XmlPullParserException pe) {
            Slog.e(LOG_TAG, "Error reading user list");
        } finally {
            IoUtils.closeQuietly(fis);
        }
        return null;
    }

    @VisibleForTesting
    UserData readUserLP(int id, InputStream is) throws IOException,
            XmlPullParserException {
        int flags = 0;
        String userType = null;
        int serialNumber = id;
        String name = null;
        String account = null;
        String iconPath = null;
        long creationTime = 0L;
        long lastLoggedInTime = 0L;
        long lastRequestQuietModeEnabledTimestamp = 0L;
        String lastLoggedInFingerprint = null;
        int profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        int profileBadge = 0;
        int restrictedProfileParentId = UserInfo.NO_PROFILE_GROUP_ID;
        boolean partial = false;
        boolean preCreated = false;
        boolean converted = false;
        boolean guestToRemove = false;
        boolean persistSeedData = false;
        String seedAccountName = null;
        String seedAccountType = null;
        PersistableBundle seedAccountOptions = null;
        Bundle baseRestrictions = null;
        Bundle legacyLocalRestrictions = null;
        RestrictionsSet localRestrictions = null;
        Bundle globalRestrictions = null;
        boolean ignorePrepareStorageErrors = true; // default is true for old users

        final TypedXmlPullParser parser = Xml.resolvePullParser(is);
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            // Skip
        }

        if (type != XmlPullParser.START_TAG) {
            Slog.e(LOG_TAG, "Unable to read user " + id);
            return null;
        }

        if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
            int storedId = parser.getAttributeInt(null, ATTR_ID, -1);
            if (storedId != id) {
                Slog.e(LOG_TAG, "User id does not match the file name");
                return null;
            }
            serialNumber = parser.getAttributeInt(null, ATTR_SERIAL_NO, id);
            flags = parser.getAttributeInt(null, ATTR_FLAGS, 0);
            userType = parser.getAttributeValue(null, ATTR_TYPE);
            userType = userType != null ? userType.intern() : null;
            iconPath = parser.getAttributeValue(null, ATTR_ICON_PATH);
            creationTime = parser.getAttributeLong(null, ATTR_CREATION_TIME, 0);
            lastLoggedInTime = parser.getAttributeLong(null, ATTR_LAST_LOGGED_IN_TIME, 0);
            lastLoggedInFingerprint = parser.getAttributeValue(null,
                    ATTR_LAST_LOGGED_IN_FINGERPRINT);
            profileGroupId = parser.getAttributeInt(null, ATTR_PROFILE_GROUP_ID,
                    UserInfo.NO_PROFILE_GROUP_ID);
            profileBadge = parser.getAttributeInt(null, ATTR_PROFILE_BADGE, 0);
            restrictedProfileParentId = parser.getAttributeInt(null,
                    ATTR_RESTRICTED_PROFILE_PARENT_ID, UserInfo.NO_PROFILE_GROUP_ID);
            partial = parser.getAttributeBoolean(null, ATTR_PARTIAL, false);
            preCreated = parser.getAttributeBoolean(null, ATTR_PRE_CREATED, false);
            converted = parser.getAttributeBoolean(null, ATTR_CONVERTED_FROM_PRE_CREATED, false);
            guestToRemove = parser.getAttributeBoolean(null, ATTR_GUEST_TO_REMOVE, false);

            seedAccountName = parser.getAttributeValue(null, ATTR_SEED_ACCOUNT_NAME);
            seedAccountType = parser.getAttributeValue(null, ATTR_SEED_ACCOUNT_TYPE);
            if (seedAccountName != null || seedAccountType != null) {
                persistSeedData = true;
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                String tag = parser.getName();
                if (TAG_NAME.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        name = parser.getText();
                    }
                } else if (TAG_RESTRICTIONS.equals(tag)) {
                    baseRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_DEVICE_POLICY_RESTRICTIONS.equals(tag)) {
                    legacyLocalRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS.equals(tag)) {
                    localRestrictions = RestrictionsSet.readRestrictions(parser,
                            TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS);
                } else if (TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS.equals(tag)) {
                    globalRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_ACCOUNT.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        account = parser.getText();
                    }
                } else if (TAG_SEED_ACCOUNT_OPTIONS.equals(tag)) {
                    seedAccountOptions = PersistableBundle.restoreFromXml(parser);
                    persistSeedData = true;
                } else if (TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        lastRequestQuietModeEnabledTimestamp = Long.parseLong(parser.getText());
                    }
                } else if (TAG_IGNORE_PREPARE_STORAGE_ERRORS.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        ignorePrepareStorageErrors = Boolean.parseBoolean(parser.getText());
                    }
                }
            }
        }

        // Create the UserInfo object that gets passed around
        UserInfo userInfo = new UserInfo(id, name, iconPath, flags, userType);
        userInfo.serialNumber = serialNumber;
        userInfo.creationTime = creationTime;
        userInfo.lastLoggedInTime = lastLoggedInTime;
        userInfo.lastLoggedInFingerprint = lastLoggedInFingerprint;
        userInfo.partial = partial;
        userInfo.preCreated = preCreated;
        userInfo.convertedFromPreCreated = converted;
        userInfo.guestToRemove = guestToRemove;
        userInfo.profileGroupId = profileGroupId;
        userInfo.profileBadge = profileBadge;
        userInfo.restrictedProfileParentId = restrictedProfileParentId;

        // Create the UserData object that's internal to this class
        UserData userData = new UserData();
        userData.info = userInfo;
        userData.account = account;
        userData.seedAccountName = seedAccountName;
        userData.seedAccountType = seedAccountType;
        userData.persistSeedData = persistSeedData;
        userData.seedAccountOptions = seedAccountOptions;
        userData.setLastRequestQuietModeEnabledMillis(lastRequestQuietModeEnabledTimestamp);
        if (ignorePrepareStorageErrors) {
            userData.setIgnorePrepareStorageErrors();
        }

        synchronized (mRestrictionsLock) {
            if (baseRestrictions != null) {
                mBaseUserRestrictions.updateRestrictions(id, baseRestrictions);
            }
            if (localRestrictions != null) {
                mDevicePolicyLocalUserRestrictions.put(id, localRestrictions);
                if (legacyLocalRestrictions != null) {
                    Slog.wtf(LOG_TAG, "Seeing both legacy and current local restrictions in xml");
                }
            } else if (legacyLocalRestrictions != null) {
                mDevicePolicyLocalUserRestrictions.put(id,
                        new RestrictionsSet(id, legacyLocalRestrictions));
            }
            if (globalRestrictions != null) {
                mDevicePolicyGlobalUserRestrictions.updateRestrictions(id,
                        globalRestrictions);
            }
        }
        return userData;
    }

    /**
     * Removes the app restrictions file for a specific package and user id, if it exists.
     *
     * @return whether there were any restrictions.
     */
    private static boolean cleanAppRestrictionsForPackageLAr(String pkg, @UserIdInt int userId) {
        final File dir = Environment.getUserSystemDirectory(userId);
        final File resFile = new File(dir, packageToRestrictionsFileName(pkg));
        if (resFile.exists()) {
            resFile.delete();
            return true;
        }
        return false;
    }

    /**
     * Creates a profile user. Used for actual profiles, like
     * {@link UserManager#USER_TYPE_PROFILE_MANAGED},
     * as well as for {@link UserManager#USER_TYPE_FULL_RESTRICTED}.
     */
    @Override
    public UserInfo createProfileForUserWithThrow(@Nullable String name, @NonNull String userType,
            @UserInfoFlag int flags, @UserIdInt int userId, @Nullable String[] disallowedPackages)
            throws ServiceSpecificException {
        checkManageOrCreateUsersPermission(flags);
        try {
            return createUserInternal(name, userType, flags, userId, disallowedPackages);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    /**
     * @see #createProfileForUser
     */
    @Override
    public UserInfo createProfileForUserEvenWhenDisallowedWithThrow(String name,
            @NonNull String userType,
            @UserInfoFlag int flags, @UserIdInt int userId, @Nullable String[] disallowedPackages)
            throws ServiceSpecificException {
        checkManageOrCreateUsersPermission(flags);
        try {
            return createUserInternalUnchecked(name, userType, flags, userId,
                    /* preCreate= */ false, disallowedPackages, /* token= */ null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    @Override
    public UserInfo createUserWithThrow(String name, @NonNull String userType,
            @UserInfoFlag int flags)
            throws ServiceSpecificException {
        checkManageOrCreateUsersPermission(flags);
        try {
            return createUserInternal(name, userType, flags, UserHandle.USER_NULL,
                    /* disallowedPackages= */ null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    @Override
    public UserInfo preCreateUserWithThrow(String userType) throws ServiceSpecificException {
        final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        final int flags = userTypeDetails != null ? userTypeDetails.getDefaultUserInfoFlags() : 0;

        checkManageOrCreateUsersPermission(flags);

        Preconditions.checkArgument(isUserTypeEligibleForPreCreation(userTypeDetails),
                "cannot pre-create user of type " + userType);
        Slog.i(LOG_TAG, "Pre-creating user of type " + userType);

        try {
            return createUserInternalUnchecked(/* name= */ null, userType, flags,
                    /* parentId= */ UserHandle.USER_NULL, /* preCreate= */ true,
                    /* disallowedPackages= */ null, /* token= */ null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    private UserInfo createUserInternal(@Nullable String name, @NonNull String userType,
            @UserInfoFlag int flags, @UserIdInt int parentId,
            @Nullable String[] disallowedPackages)
            throws UserManager.CheckedUserOperationException {
        String restriction = (UserManager.isUserTypeManagedProfile(userType))
                ? UserManager.DISALLOW_ADD_MANAGED_PROFILE
                : UserManager.DISALLOW_ADD_USER;
        enforceUserRestriction(restriction, UserHandle.getCallingUserId(),
                "Cannot add user");
        return createUserInternalUnchecked(name, userType, flags, parentId,
                /* preCreate= */ false, disallowedPackages, /* token= */ null);
    }

    private UserInfo createUserInternalUnchecked(@Nullable String name,
            @NonNull String userType, @UserInfoFlag int flags, @UserIdInt int parentId,
            boolean preCreate, @Nullable String[] disallowedPackages,
            @Nullable Object token)
            throws UserManager.CheckedUserOperationException {
        final int nextProbableUserId = getNextAvailableId();
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("createUser-" + flags);
        final long sessionId = logUserCreateJourneyBegin(nextProbableUserId, userType, flags);
        UserInfo newUser = null;
        try {
            newUser = createUserInternalUncheckedNoTracing(name, userType, flags, parentId,
                        preCreate, disallowedPackages, t, token);
            return newUser;
        } finally {
            logUserCreateJourneyFinish(sessionId, nextProbableUserId, newUser != null);
            t.traceEnd();
        }
    }

    private UserInfo createUserInternalUncheckedNoTracing(@Nullable String name,
            @NonNull String userType, @UserInfoFlag int flags, @UserIdInt int parentId,
            boolean preCreate, @Nullable String[] disallowedPackages,
            @NonNull TimingsTraceAndSlog t, @Nullable Object token)
                    throws UserManager.CheckedUserOperationException {
        final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        if (userTypeDetails == null) {
            Slog.e(LOG_TAG, "Cannot create user of invalid user type: " + userType);
            return null;
        }
        userType = userType.intern(); // Now that we know it's valid, we can intern it.
        flags |= userTypeDetails.getDefaultUserInfoFlags();
        if (!checkUserTypeConsistency(flags)) {
            Slog.e(LOG_TAG, "Cannot add user. Flags (" + Integer.toHexString(flags)
                    + ") and userTypeDetails (" + userType +  ") are inconsistent.");
            return null;
        }
        if ((flags & UserInfo.FLAG_SYSTEM) != 0) {
            Slog.e(LOG_TAG, "Cannot add user. Flags (" + Integer.toHexString(flags)
                    + ") indicated SYSTEM user, which cannot be created.");
            return null;
        }
        synchronized (mUsersLock) {
            if (mForceEphemeralUsers) {
                flags |= UserInfo.FLAG_EPHEMERAL;
            }
        }

        // Try to use a pre-created user (if available).
        if (!preCreate && parentId < 0 && isUserTypeEligibleForPreCreation(userTypeDetails)) {
            final UserInfo preCreatedUser = convertPreCreatedUserIfPossible(userType, flags, name,
                    token);
            if (preCreatedUser != null) {
                return preCreatedUser;
            }
        }

        DeviceStorageMonitorInternal dsm = LocalServices
                .getService(DeviceStorageMonitorInternal.class);
        if (dsm.isMemoryLow()) {
            throwCheckedUserOperationException("Cannot add user. Not enough space on disk.",
                    UserManager.USER_OPERATION_ERROR_LOW_STORAGE);
        }

        final boolean isProfile = userTypeDetails.isProfile();
        final boolean isGuest = UserManager.isUserTypeGuest(userType);
        final boolean isRestricted = UserManager.isUserTypeRestricted(userType);
        final boolean isDemo = UserManager.isUserTypeDemo(userType);

        final long ident = Binder.clearCallingIdentity();
        UserInfo userInfo;
        UserData userData;
        final int userId;
        try {
            synchronized (mPackagesLock) {
                UserData parent = null;
                if (parentId != UserHandle.USER_NULL) {
                    synchronized (mUsersLock) {
                        parent = getUserDataLU(parentId);
                    }
                    if (parent == null) {
                        throwCheckedUserOperationException(
                                "Cannot find user data for parent user " + parentId,
                                UserManager.USER_OPERATION_ERROR_UNKNOWN);
                    }
                }
                if (!preCreate && !canAddMoreUsersOfType(userTypeDetails)) {
                    throwCheckedUserOperationException("Cannot add more users of type " + userType
                                    + ". Maximum number of that type already exists.",
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                // TODO(b/142482943): Perhaps let the following code apply to restricted users too.
                if (isProfile && !canAddMoreProfilesToUser(userType, parentId, false)) {
                    throwCheckedUserOperationException(
                            "Cannot add more profiles of type " + userType
                                    + " for user " + parentId,
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                if (!isGuest && !isProfile && !isDemo && isUserLimitReached()) {
                    // If we're not adding a guest/demo user or a profile and the 'user limit' has
                    // been reached, cannot add a user.
                    throwCheckedUserOperationException(
                            "Cannot add user. Maximum user limit is reached.",
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                // In legacy mode, restricted profile's parent can only be the owner user
                if (isRestricted && !UserManager.isSplitSystemUser()
                        && (parentId != UserHandle.USER_SYSTEM)) {
                    throwCheckedUserOperationException(
                            "Cannot add restricted profile - parent user must be owner",
                            UserManager.USER_OPERATION_ERROR_UNKNOWN);
                }
                if (isRestricted && UserManager.isSplitSystemUser()) {
                    if (parent == null) {
                        throwCheckedUserOperationException(
                                "Cannot add restricted profile - parent user must be specified",
                                UserManager.USER_OPERATION_ERROR_UNKNOWN);
                    }
                    if (!parent.info.canHaveProfile()) {
                        throwCheckedUserOperationException(
                                "Cannot add restricted profile - profiles cannot be created for "
                                        + "the specified parent user id "
                                        + parentId,
                                UserManager.USER_OPERATION_ERROR_UNKNOWN);
                    }
                }

                userId = getNextAvailableId();
                Slog.i(LOG_TAG, "Creating user " + userId + " of type " + userType);
                Environment.getUserSystemDirectory(userId).mkdirs();

                synchronized (mUsersLock) {
                    // Inherit ephemeral flag from parent.
                    if (parent != null && parent.info.isEphemeral()) {
                        flags |= UserInfo.FLAG_EPHEMERAL;
                    }

                    // Always clear EPHEMERAL for pre-created users, otherwise the storage key
                    // won't be persisted. The flag will be re-added (if needed) when the
                    // pre-created user is "converted" to a normal user.
                    if (preCreate) {
                        flags &= ~UserInfo.FLAG_EPHEMERAL;
                    }

                    userInfo = new UserInfo(userId, name, null, flags, userType);
                    userInfo.serialNumber = mNextSerialNumber++;
                    userInfo.creationTime = getCreationTime();
                    userInfo.partial = true;
                    userInfo.preCreated = preCreate;
                    userInfo.lastLoggedInFingerprint = Build.VERSION.INCREMENTAL;
                    if (userTypeDetails.hasBadge() && parentId != UserHandle.USER_NULL) {
                        userInfo.profileBadge = getFreeProfileBadgeLU(parentId, userType);
                    }
                    userData = new UserData();
                    userData.info = userInfo;
                    mUsers.put(userId, userData);
                }
                writeUserLP(userData);
                writeUserListLP();
                if (parent != null) {
                    if (isProfile) {
                        if (parent.info.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.info.profileGroupId = parent.info.id;
                            writeUserLP(parent);
                        }
                        userInfo.profileGroupId = parent.info.profileGroupId;
                    } else if (isRestricted) {
                        if (parent.info.restrictedProfileParentId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.info.restrictedProfileParentId = parent.info.id;
                            writeUserLP(parent);
                        }
                        userInfo.restrictedProfileParentId = parent.info.restrictedProfileParentId;
                    }
                }
            }

            t.traceBegin("createUserKey");
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            storage.createUserKey(userId, userInfo.serialNumber, userInfo.isEphemeral());
            t.traceEnd();

            t.traceBegin("prepareUserData");
            mUserDataPreparer.prepareUserData(userId, userInfo.serialNumber,
                    StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
            t.traceEnd();

            final Set<String> userTypeInstallablePackages =
                    mSystemPackageInstaller.getInstallablePackagesForUserType(userType);
            t.traceBegin("PM.createNewUser");
            mPm.createNewUser(userId, userTypeInstallablePackages, disallowedPackages);
            t.traceEnd();

            userInfo.partial = false;
            synchronized (mPackagesLock) {
                writeUserLP(userData);
            }
            updateUserIds();

            Bundle restrictions = new Bundle();
            if (isGuest) {
                // Guest default restrictions can be modified via setDefaultGuestRestrictions.
                synchronized (mGuestRestrictions) {
                    restrictions.putAll(mGuestRestrictions);
                }
            } else {
                userTypeDetails.addDefaultRestrictionsTo(restrictions);
            }
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.updateRestrictions(userId, restrictions);
            }

            t.traceBegin("PM.onNewUserCreated-" + userId);
            mPm.onNewUserCreated(userId, /* convertedFromPreCreated= */ false);
            t.traceEnd();
            applyDefaultUserSettings(userTypeDetails, userId);
            setDefaultCrossProfileIntentFilters(userId, userTypeDetails, restrictions, parentId);

            if (preCreate) {
                // Must start user (which will be stopped right away, through
                // UserController.finishUserUnlockedCompleted) so services can properly
                // intialize it.
                // NOTE: user will be stopped on UserController.finishUserUnlockedCompleted().
                Slog.i(LOG_TAG, "starting pre-created user " + userInfo.toFullString());
                final IActivityManager am = ActivityManager.getService();
                try {
                    am.startUserInBackground(userId);
                } catch (RemoteException e) {
                    Slog.w(LOG_TAG, "could not start pre-created user " + userId, e);
                }
            } else {
                dispatchUserAdded(userInfo, token);
            }

        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        // TODO(b/143092698): it's possible to reach "max users overflow" when the user is created
        // "from scratch" (i.e., not from a pre-created user) and reaches the maximum number of
        // users without counting the pre-created one. Then when the pre-created is converted, the
        // "effective" number of max users is exceeds. Example:
        // Max: 3 Current: 2 full (u0 and u10) + 1 pre-created (u11)
        // Step 1: create(/* flags doesn't match u11 */): u12 is created, "effective max" is now 3
        //         (u0, u10, u12) but "real" max is 4 (u0, u10, u11, u12)
        // Step 2: create(/* flags match u11 */): u11 is converted, now "effective max" is also 4
        //         (u0, u10, u11, u12)
        // One way to avoid this issue is by removing a pre-created user from the pool when the
        // "real" max exceeds the max here.

        return userInfo;
    }

    private void applyDefaultUserSettings(UserTypeDetails userTypeDetails, @UserIdInt int userId) {
        final Bundle systemSettings = userTypeDetails.getDefaultSystemSettings();
        final Bundle secureSettings = userTypeDetails.getDefaultSecureSettings();
        if (systemSettings.isEmpty() && secureSettings.isEmpty()) {
            return;
        }

        final int systemSettingsSize = systemSettings.size();
        final String[] systemSettingsArray = systemSettings.keySet().toArray(
                new String[systemSettingsSize]);
        for (int i = 0; i < systemSettingsSize; i++) {
            final String setting = systemSettingsArray[i];
            if (!Settings.System.putStringForUser(
                    mContext.getContentResolver(), setting, systemSettings.getString(setting),
                    userId)) {
                Slog.e(LOG_TAG, "Failed to insert default system setting: " + setting);
            }
        }

        final int secureSettingsSize = secureSettings.size();
        final String[] secureSettingsArray = secureSettings.keySet().toArray(
                new String[secureSettingsSize]);
        for (int i = 0; i < secureSettingsSize; i++) {
            final String setting = secureSettingsArray[i];
            if (!Settings.Secure.putStringForUser(
                    mContext.getContentResolver(), setting, secureSettings.getString(setting),
                    userId)) {
                Slog.e(LOG_TAG, "Failed to insert default secure setting: " + setting);
            }
        }
    }

    /**
     * Sets all default cross profile intent filters between {@code parentUserId} and
     * {@code profileUserId}, does nothing if {@code userType} is not a profile.
     */
    private void setDefaultCrossProfileIntentFilters(
            @UserIdInt int profileUserId, UserTypeDetails profileDetails,
            Bundle profileRestrictions, @UserIdInt int parentUserId) {
        if (profileDetails == null || !profileDetails.isProfile()) {
            return;
        }
        final List<DefaultCrossProfileIntentFilter> filters =
                profileDetails.getDefaultCrossProfileIntentFilters();
        if (filters.isEmpty()) {
            return;
        }

        // Skip filters that allow data to be shared into the profile, if admin has disabled it.
        final boolean disallowSharingIntoProfile =
                profileRestrictions.getBoolean(
                        UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
                        /* defaultValue = */ false);
        final int size = profileDetails.getDefaultCrossProfileIntentFilters().size();
        for (int i = 0; i < size; i++) {
            final DefaultCrossProfileIntentFilter filter =
                    profileDetails.getDefaultCrossProfileIntentFilters().get(i);
            if (disallowSharingIntoProfile && filter.letsPersonalDataIntoProfile) {
                continue;
            }
            if (filter.direction == DefaultCrossProfileIntentFilter.Direction.TO_PARENT) {
                mPm.addCrossProfileIntentFilter(
                        filter.filter, mContext.getOpPackageName(), profileUserId, parentUserId,
                        filter.flags);
            } else {
                mPm.addCrossProfileIntentFilter(
                        filter.filter, mContext.getOpPackageName(), parentUserId, profileUserId,
                        filter.flags);
            }
        }
    }

    /**
     * Finds and converts a previously pre-created user into a regular user, if possible.
     *
     * @return the converted user, or {@code null} if no pre-created user could be converted.
     */
    private @Nullable UserInfo convertPreCreatedUserIfPossible(String userType,
            @UserInfoFlag int flags, String name, @Nullable Object token) {
        final UserData preCreatedUserData;
        synchronized (mUsersLock) {
            preCreatedUserData = getPreCreatedUserLU(userType);
        }
        if (preCreatedUserData == null) {
            return null;
        }
        synchronized (mUserStates) {
            if (mUserStates.has(preCreatedUserData.info.id)) {
                Slog.w(LOG_TAG, "Cannot reuse pre-created user "
                        + preCreatedUserData.info.id + " because it didn't stop yet");
                return null;
            }
        }
        final UserInfo preCreatedUser = preCreatedUserData.info;
        final int newFlags = preCreatedUser.flags | flags;
        if (!checkUserTypeConsistency(newFlags)) {
            Slog.wtf(LOG_TAG, "Cannot reuse pre-created user " + preCreatedUser.id
                    + " of type " + userType + " because flags are inconsistent. "
                    + "Flags (" + Integer.toHexString(flags) + "); preCreatedUserFlags ( "
                    + Integer.toHexString(preCreatedUser.flags) + ").");
            return null;
        }
        Slog.i(LOG_TAG, "Reusing pre-created user " + preCreatedUser.id + " of type "
                + userType + " and bestowing on it flags " + UserInfo.flagsToString(flags));
        preCreatedUser.name = name;
        preCreatedUser.flags = newFlags;
        preCreatedUser.preCreated = false;
        preCreatedUser.convertedFromPreCreated = true;
        preCreatedUser.creationTime = getCreationTime();

        synchronized (mPackagesLock) {
            writeUserLP(preCreatedUserData);
            writeUserListLP();
        }
        updateUserIds();
        mPm.onNewUserCreated(preCreatedUser.id, /* convertedFromPreCreated= */ true);
        dispatchUserAdded(preCreatedUser, token);
        return preCreatedUser;
    }

    /** Checks that the flags do not contain mutually exclusive types/properties. */
    static boolean checkUserTypeConsistency(@UserInfoFlag int flags) {
        // Mask to check that flags don't refer to multiple user types.
        final int userTypeFlagMask = UserInfo.FLAG_GUEST | UserInfo.FLAG_DEMO
                | UserInfo.FLAG_RESTRICTED | UserInfo.FLAG_PROFILE;
        return isAtMostOneFlag(flags & userTypeFlagMask)
                && isAtMostOneFlag(flags & (UserInfo.FLAG_PROFILE | UserInfo.FLAG_FULL))
                && isAtMostOneFlag(flags & (UserInfo.FLAG_PROFILE | UserInfo.FLAG_SYSTEM));
    }

    /** Returns whether the given flags contains at most one 1. */
    private static boolean isAtMostOneFlag(int flags) {
        return (flags & (flags - 1)) == 0;
        // If !=0, this means that flags is not a power of 2, and therefore is multiple types.
    }

    /** Install/uninstall system packages for all users based on their user-type, as applicable. */
    boolean installWhitelistedSystemPackages(boolean isFirstBoot, boolean isUpgrade,
            @Nullable ArraySet<String> existingPackages) {
        return mSystemPackageInstaller.installWhitelistedSystemPackages(
                isFirstBoot, isUpgrade, existingPackages);
    }

    @Override
    public String[] getPreInstallableSystemPackages(@NonNull String userType) {
        checkManageOrCreateUsersPermission("getPreInstallableSystemPackages");
        final Set<String> installableSystemPackages =
                mSystemPackageInstaller.getInstallablePackagesForUserType(userType);
        if (installableSystemPackages == null) {
            return null;
        }
        return installableSystemPackages.toArray(new String[installableSystemPackages.size()]);
    }

    private long getCreationTime() {
        final long now = System.currentTimeMillis();
        return (now > EPOCH_PLUS_30_YEARS) ? now : 0;
    }

    private void dispatchUserAdded(@NonNull UserInfo userInfo, @Nullable Object token) {
        // Notify internal listeners first...
        synchronized (mUserLifecycleListeners) {
            for (int i = 0; i < mUserLifecycleListeners.size(); i++) {
                mUserLifecycleListeners.get(i).onUserCreated(userInfo, token);
            }
        }

        //...then external ones
        Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
        addedIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userInfo.id);
        // Also, add the UserHandle for mainline modules which can't use the @hide
        // EXTRA_USER_HANDLE.
        addedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userInfo.id));
        mContext.sendBroadcastAsUser(addedIntent, UserHandle.ALL,
                android.Manifest.permission.MANAGE_USERS);
        MetricsLogger.count(mContext, userInfo.isGuest() ? TRON_GUEST_CREATED
                : (userInfo.isDemo() ? TRON_DEMO_CREATED : TRON_USER_CREATED), 1);

        if (!userInfo.isProfile()) {
            // If the user switch hasn't been explicitly toggled on or off by the user, turn it on.
            if (android.provider.Settings.Global.getString(mContext.getContentResolver(),
                    android.provider.Settings.Global.USER_SWITCHER_ENABLED) == null) {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                        android.provider.Settings.Global.USER_SWITCHER_ENABLED, 1);
            }
        }
    }

    /**
     * Gets a pre-created user for the given user type.
     *
     * <p>Should be used only during user creation, so the pre-created user can be used (instead of
     * creating and initializing a new user from scratch).
     */
    // TODO(b/143092698): add unit test
    @GuardedBy("mUsersLock")
    private @Nullable UserData getPreCreatedUserLU(String userType) {
        if (DBG) Slog.d(LOG_TAG, "getPreCreatedUser(): userType= " + userType);
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            final UserData user = mUsers.valueAt(i);
            if (DBG) Slog.d(LOG_TAG, i + ":" + user.info.toFullString());
            if (user.info.preCreated && user.info.userType.equals(userType)) {
                if (!user.info.isInitialized()) {
                    Slog.w(LOG_TAG, "found pre-created user of type " + userType
                            + ", but it's not initialized yet: " + user.info.toFullString());
                    continue;
                }
                return user;
            }
        }
        return null;
    }

    /**
     * Returns whether a user with the given userTypeDetails is eligible to be
     * {@link UserInfo#preCreated}.
     */
    private static boolean isUserTypeEligibleForPreCreation(UserTypeDetails userTypeDetails) {
        if (userTypeDetails == null) {
            return false;
        }
        return !userTypeDetails.isProfile()
                && !userTypeDetails.getName().equals(UserManager.USER_TYPE_FULL_RESTRICTED);
    }

    private long logUserCreateJourneyBegin(@UserIdInt int userId, String userType,
            @UserInfoFlag int flags) {
        final long sessionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        // log the journey atom with the user metadata
        FrameworkStatsLog.write(FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED, sessionId,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_CREATE,
                /* origin_user= */ -1, userId, UserManager.getUserTypeForStatsd(userType), flags);
        // log the event atom to indicate the event start
        FrameworkStatsLog.write(FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED, sessionId, userId,
                FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__CREATE_USER,
                FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__BEGIN);
        return sessionId;
    }

    private void logUserCreateJourneyFinish(long sessionId, @UserIdInt int userId, boolean finish) {
        FrameworkStatsLog.write(FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED, sessionId, userId,
                FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__CREATE_USER,
                finish ? FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__FINISH
                        : FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__NONE);
    }

    @VisibleForTesting
    UserData putUserInfo(UserInfo userInfo) {
        final UserData userData = new UserData();
        userData.info = userInfo;
        synchronized (mUsersLock) {
            mUsers.put(userInfo.id, userData);
        }
        return userData;
    }

    @VisibleForTesting
    void removeUserInfo(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            mUsers.remove(userId);
        }
    }

    /**
     * @hide
     */
    @Override
    public UserInfo createRestrictedProfileWithThrow(@Nullable String name, int parentUserId) {
        checkManageOrCreateUsersPermission("setupRestrictedProfile");
        final UserInfo user = createProfileForUserWithThrow(
                name, UserManager.USER_TYPE_FULL_RESTRICTED, 0, parentUserId, null);
        if (user == null) {
            return null;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true, user.id);
            // Change the setting before applying the DISALLOW_SHARE_LOCATION restriction, otherwise
            // the putIntForUser() will fail.
            android.provider.Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF, user.id);
            setUserRestriction(UserManager.DISALLOW_SHARE_LOCATION, true, user.id);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return user;
    }

    /**
     * Find the current guest user. If the Guest user is partial,
     * then do not include it in the results as it is about to die.
     *
     * @return The current guest user.  Null if it doesn't exist.
     * @hide
     */
    @Override
    public UserInfo findCurrentGuestUser() {
        checkManageUsersPermission("findCurrentGuestUser");
        synchronized (mUsersLock) {
            final int size = mUsers.size();
            for (int i = 0; i < size; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.isGuest() && !user.guestToRemove && !user.preCreated
                        && !mRemovingUserIds.get(user.id)) {
                    return user;
                }
            }
        }
        return null;
    }

    /**
     * Mark this guest user for deletion to allow us to create another guest
     * and switch to that user before actually removing this guest.
     * @param userId the userid of the current guest
     * @return whether the user could be marked for deletion
     */
    @Override
    public boolean markGuestForDeletion(@UserIdInt int userId) {
        checkManageUsersPermission("Only the system can remove users");
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(
                UserManager.DISALLOW_REMOVE_USER, false)) {
            Slog.w(LOG_TAG, "Cannot remove user. DISALLOW_REMOVE_USER is enabled.");
            return false;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            final UserData userData;
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    userData = mUsers.get(userId);
                    if (userId == 0 || userData == null || mRemovingUserIds.get(userId)) {
                        return false;
                    }
                }
                if (!userData.info.isGuest()) {
                    return false;
                }
                // We set this to a guest user that is to be removed. This is a temporary state
                // where we are allowed to add new Guest users, even if this one is still not
                // removed. This user will still show up in getUserInfo() calls.
                // If we don't get around to removing this Guest user, it will be purged on next
                // startup.
                userData.info.guestToRemove = true;
                // Mark it as disabled, so that it isn't returned any more when
                // profiles are queried.
                userData.info.flags |= UserInfo.FLAG_DISABLED;
                writeUserLP(userData);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return true;
    }

    /**
     * Removes a user and all data directories created for that user. This method should be called
     * after the user's processes have been terminated.
     * @param userId the user's id
     */
    @Override
    public boolean removeUser(@UserIdInt int userId) {
        Slog.i(LOG_TAG, "removeUser u" + userId);
        checkManageOrCreateUsersPermission("Only the system can remove users");

        final String restriction = getUserRemovalRestriction(userId);
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(restriction, false)) {
            Slog.w(LOG_TAG, "Cannot remove user. " + restriction + " is enabled.");
            return false;
        }
        return removeUserUnchecked(userId);
    }

    @Override
    public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
        checkManageOrCreateUsersPermission("Only the system can remove users");
        return removeUserUnchecked(userId);
    }

    /**
     * Returns the string name of the restriction to check for user removal. The restriction name
     * varies depending on whether the user is a managed profile.
     */
    private String getUserRemovalRestriction(@UserIdInt int userId) {
        final boolean isManagedProfile;
        final UserInfo userInfo;
        synchronized (mUsersLock) {
            userInfo = getUserInfoLU(userId);
        }
        isManagedProfile = userInfo != null && userInfo.isManagedProfile();
        return isManagedProfile
                ? UserManager.DISALLOW_REMOVE_MANAGED_PROFILE : UserManager.DISALLOW_REMOVE_USER;
    }

    private boolean removeUserUnchecked(@UserIdInt int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            final UserData userData;
            int currentUser = ActivityManager.getCurrentUser();
            if (currentUser == userId) {
                Slog.w(LOG_TAG, "Current user cannot be removed.");
                return false;
            }
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    userData = mUsers.get(userId);
                    if (userId == UserHandle.USER_SYSTEM) {
                        Slog.e(LOG_TAG, "System user cannot be removed.");
                        return false;
                    }

                    if (userData == null) {
                        Slog.e(LOG_TAG, String.format(
                                "Cannot remove user %d, invalid user id provided.", userId));
                        return false;
                    }

                    if (mRemovingUserIds.get(userId)) {
                        Slog.e(LOG_TAG, String.format(
                                "User %d is already scheduled for removal.", userId));
                        return false;
                    }

                    addRemovingUserIdLocked(userId);
                }

                // Set this to a partially created user, so that the user will be purged
                // on next startup, in case the runtime stops now before stopping and
                // removing the user completely.
                userData.info.partial = true;
                // Mark it as disabled, so that it isn't returned any more when
                // profiles are queried.
                userData.info.flags |= UserInfo.FLAG_DISABLED;
                writeUserLP(userData);
            }
            try {
                mAppOpsService.removeUser(userId);
            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Unable to notify AppOpsService of removing user.", e);
            }

            // TODO(b/142482943): Send some sort of broadcast for profiles even if non-managed?
            if (userData.info.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && userData.info.isManagedProfile()) {
                // Send broadcast to notify system that the user removed was a
                // managed user.
                sendProfileRemovedBroadcast(userData.info.profileGroupId, userData.info.id);
            }

            if (DBG) Slog.i(LOG_TAG, "Stopping user " + userId);
            int res;
            try {
                res = ActivityManager.getService().stopUser(userId, /* force= */ true,
                new IStopUserCallback.Stub() {
                            @Override
                            public void userStopped(int userIdParam) {
                                finishRemoveUser(userIdParam);
                            }
                            @Override
                            public void userStopAborted(int userIdParam) {
                            }
                        });
            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Failed to stop user during removal.", e);
                return false;
            }
            return res == ActivityManager.USER_OP_SUCCESS;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
    void addRemovingUserIdLocked(@UserIdInt int userId) {
        // We remember deleted user IDs to prevent them from being
        // reused during the current boot; they can still be reused
        // after a reboot or recycling of userIds.
        mRemovingUserIds.put(userId, true);
        mRecentlyRemovedIds.add(userId);
        // Keep LRU queue of recently removed IDs for recycling
        if (mRecentlyRemovedIds.size() > MAX_RECENTLY_REMOVED_IDS_SIZE) {
            mRecentlyRemovedIds.removeFirst();
        }
    }

    @Override
    public @UserManager.RemoveResult int removeUserOrSetEphemeral(@UserIdInt int userId,
            boolean evenWhenDisallowed) {
        checkManageOrCreateUsersPermission("Only the system can remove users");

        if (!evenWhenDisallowed) {
            final String restriction = getUserRemovalRestriction(userId);
            if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(restriction, false)) {
                Slog.w(LOG_TAG, "Cannot remove user. " + restriction + " is enabled.");
                return UserManager.REMOVE_RESULT_ERROR;
            }
        }
        if (userId == UserHandle.USER_SYSTEM) {
            Slog.e(LOG_TAG, "System user cannot be removed.");
            return UserManager.REMOVE_RESULT_ERROR;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            final UserData userData;
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    userData = mUsers.get(userId);
                    if (userData == null) {
                        Slog.e(LOG_TAG,
                                "Cannot remove user " + userId + ", invalid user id provided.");
                        return UserManager.REMOVE_RESULT_ERROR;
                    }

                    if (mRemovingUserIds.get(userId)) {
                        Slog.e(LOG_TAG, "User " + userId + " is already scheduled for removal.");
                        return UserManager.REMOVE_RESULT_ALREADY_BEING_REMOVED;
                    }
                }

                // Attempt to immediately remove a non-current user
                final int currentUser = ActivityManager.getCurrentUser();
                if (currentUser != userId) {
                    // Attempt to remove the user. This will fail if the user is the current user
                    if (removeUserUnchecked(userId)) {
                        return UserManager.REMOVE_RESULT_REMOVED;
                    }
                }
                // If the user was not immediately removed, make sure it is marked as ephemeral.
                // Don't mark as disabled since, per UserInfo.FLAG_DISABLED documentation, an
                // ephemeral user should only be marked as disabled when its removal is in progress.
                Slog.i(LOG_TAG, "Unable to immediately remove user " + userId + " (current user is "
                        + currentUser + "). User is set as ephemeral and will be removed on user "
                        + "switch or reboot.");
                userData.info.flags |= UserInfo.FLAG_EPHEMERAL;
                writeUserLP(userData);

                return UserManager.REMOVE_RESULT_SET_EPHEMERAL;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void finishRemoveUser(final @UserIdInt int userId) {
        if (DBG) Slog.i(LOG_TAG, "finishRemoveUser " + userId);

        UserInfo user;
        synchronized (mUsersLock) {
            user = getUserInfoLU(userId);
        }
        if (user != null && user.preCreated) {
            Slog.i(LOG_TAG, "Removing a pre-created user with user id: " + userId);
            // Don't want to fire ACTION_USER_REMOVED, so cleanup the state and exit early.
            LocalServices.getService(ActivityTaskManagerInternal.class).onUserStopped(userId);
            removeUserState(userId);
            return;
        }

        synchronized (mUserLifecycleListeners) {
            for (int i = 0; i < mUserLifecycleListeners.size(); i++) {
                mUserLifecycleListeners.get(i).onUserRemoved(user);
            }
        }

        // Let other services shutdown any activity and clean up their state before completely
        // wiping the user's system directory and removing from the user list
        final long ident = Binder.clearCallingIdentity();
        try {
            Intent removedIntent = new Intent(Intent.ACTION_USER_REMOVED);
            removedIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            removedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            // Also, add the UserHandle for mainline modules which can't use the @hide
            // EXTRA_USER_HANDLE.
            removedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
            mContext.sendOrderedBroadcastAsUser(removedIntent, UserHandle.ALL,
                    android.Manifest.permission.MANAGE_USERS,

                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (DBG) {
                                Slog.i(LOG_TAG,
                                        "USER_REMOVED broadcast sent, cleaning up user data "
                                        + userId);
                            }
                            new Thread() {
                                @Override
                                public void run() {
                                    LocalServices.getService(ActivityManagerInternal.class)
                                            .onUserRemoved(userId);
                                    removeUserState(userId);
                                }
                            }.start();
                        }
                    },

                    null, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeUserState(final @UserIdInt int userId) {
        try {
            mContext.getSystemService(StorageManager.class).destroyUserKey(userId);
        } catch (IllegalStateException e) {
            // This may be simply because the user was partially created.
            Slog.i(LOG_TAG, "Destroying key for user " + userId + " failed, continuing anyway", e);
        }

        // Cleanup gatekeeper secure user id
        try {
            final IGateKeeperService gk = GateKeeper.getService();
            if (gk != null) {
                gk.clearSecureUserId(userId);
            }
        } catch (Exception ex) {
            Slog.w(LOG_TAG, "unable to clear GK secure user id");
        }

        // Cleanup package manager settings
        mPm.cleanUpUser(this, userId);

        // Clean up all data before removing metadata
        mUserDataPreparer.destroyUserData(userId,
                StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);

        // Remove this user from the list
        synchronized (mUsersLock) {
            mUsers.remove(userId);
            mIsUserManaged.delete(userId);
        }
        synchronized (mUserStates) {
            mUserStates.delete(userId);
        }
        synchronized (mRestrictionsLock) {
            mBaseUserRestrictions.remove(userId);
            mAppliedUserRestrictions.remove(userId);
            mCachedEffectiveUserRestrictions.remove(userId);
            // Remove local restrictions affecting user
            mDevicePolicyLocalUserRestrictions.delete(userId);
            // Remove local restrictions set by user
            boolean changed = false;
            for (int i = 0; i < mDevicePolicyLocalUserRestrictions.size(); i++) {
                int targetUserId = mDevicePolicyLocalUserRestrictions.keyAt(i);
                changed |= getDevicePolicyLocalRestrictionsForTargetUserLR(targetUserId)
                        .remove(userId);
            }
            changed |= mDevicePolicyGlobalUserRestrictions.remove(userId);
            if (changed) {
                applyUserRestrictionsForAllUsersLR();
            }
        }
        // Update the user list
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
        // Remove user file
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userId + XML_SUFFIX));
        userFile.delete();
        updateUserIds();
        if (RELEASE_DELETED_USER_ID) {
            synchronized (mUsersLock) {
                mRemovingUserIds.delete(userId);
            }
        }
    }

    private void sendProfileRemovedBroadcast(int parentUserId, int removedUserId) {
        Intent managedProfileIntent = new Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        managedProfileIntent.putExtra(Intent.EXTRA_USER, new UserHandle(removedUserId));
        managedProfileIntent.putExtra(Intent.EXTRA_USER_HANDLE, removedUserId);
        final UserHandle parentHandle = new UserHandle(parentUserId);
        getDevicePolicyManagerInternal().broadcastIntentToCrossProfileManifestReceiversAsUser(
                managedProfileIntent, parentHandle, /* requiresPermission= */ false);
        managedProfileIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(managedProfileIntent, parentHandle,
                /* receiverPermission= */null);
    }

    @Override
    public Bundle getApplicationRestrictions(String packageName) {
        return getApplicationRestrictionsForUser(packageName, UserHandle.getCallingUserId());
    }

    @Override
    public Bundle getApplicationRestrictionsForUser(String packageName, @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId
                || !UserHandle.isSameApp(Binder.getCallingUid(), getUidForPackage(packageName))) {
            checkSystemOrRoot("get application restrictions for other user/app " + packageName);
        }
        synchronized (mAppRestrictionsLock) {
            // Read the restrictions from XML
            return readApplicationRestrictionsLAr(packageName, userId);
        }
    }

    @Override
    public void setApplicationRestrictions(String packageName, Bundle restrictions,
            @UserIdInt int userId) {
        checkSystemOrRoot("set application restrictions");
        String validationResult = validateName(packageName);
        if (validationResult != null) {
            if (packageName.contains("../")) {
                EventLog.writeEvent(0x534e4554, "239701237", -1, "");
            }
            throw new IllegalArgumentException("Invalid package name: " + validationResult);
        }
        if (restrictions != null) {
            restrictions.setDefusable(true);
        }
        final boolean changed;
        synchronized (mAppRestrictionsLock) {
            if (restrictions == null || restrictions.isEmpty()) {
                changed = cleanAppRestrictionsForPackageLAr(packageName, userId);
            } else {
                // Write the restrictions to XML
                writeApplicationRestrictionsLAr(packageName, restrictions, userId);
                // TODO(b/154323615): avoid unnecessary broadcast when there is no change.
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        // Notify package of changes via an intent - only sent to explicitly registered receivers.
        final Intent changeIntent = new Intent(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);
        changeIntent.setPackage(packageName);
        changeIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changeIntent, UserHandle.of(userId));
    }

    /**
     * Check if the given name is valid.
     *
     * Note: the logic is taken from FrameworkParsingPackageUtils in master, edited to remove
     * unnecessary parts. Copied here for a security fix.
     *
     * @param name The name to check.
     * @return null if it's valid, error message if not
     */
    @VisibleForTesting
    static String validateName(String name) {
        final int n = name.length();
        boolean front = true;
        for (int i = 0; i < n; i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                front = false;
                continue;
            }
            if (!front) {
                if ((c >= '0' && c <= '9') || c == '_') {
                    continue;
                }
                if (c == '.') {
                    front = true;
                    continue;
                }
            }
            return "bad character '" + c + "'";
        }
        return null;
    }

    private int getUidForPackage(String packageName) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.MATCH_ANY_USER).uid;
        } catch (NameNotFoundException nnfe) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mAppRestrictionsLock")
    private static Bundle readApplicationRestrictionsLAr(String packageName,
            @UserIdInt int userId) {
        AtomicFile restrictionsFile =
                new AtomicFile(new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        return readApplicationRestrictionsLAr(restrictionsFile);
    }

    @VisibleForTesting
    @GuardedBy("mAppRestrictionsLock")
    static Bundle readApplicationRestrictionsLAr(AtomicFile restrictionsFile) {
        final Bundle restrictions = new Bundle();
        final ArrayList<String> values = new ArrayList<>();
        if (!restrictionsFile.getBaseFile().exists()) {
            return restrictions;
        }

        FileInputStream fis = null;
        try {
            fis = restrictionsFile.openRead();
            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            XmlUtils.nextElement(parser);
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read restrictions file "
                        + restrictionsFile.getBaseFile());
                return restrictions;
            }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                readEntry(restrictions, values, parser);
            }
        } catch (IOException|XmlPullParserException e) {
            Slog.w(LOG_TAG, "Error parsing " + restrictionsFile.getBaseFile(), e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
        return restrictions;
    }

    private static void readEntry(Bundle restrictions, ArrayList<String> values,
            TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_ENTRY)) {
            String key = parser.getAttributeValue(null, ATTR_KEY);
            String valType = parser.getAttributeValue(null, ATTR_VALUE_TYPE);
            int count = parser.getAttributeInt(null, ATTR_MULTIPLE, -1);
            if (count != -1) {
                values.clear();
                while (count > 0 && (type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG
                            && parser.getName().equals(TAG_VALUE)) {
                        values.add(parser.nextText().trim());
                        count--;
                    }
                }
                String [] valueStrings = new String[values.size()];
                values.toArray(valueStrings);
                restrictions.putStringArray(key, valueStrings);
            } else if (ATTR_TYPE_BUNDLE.equals(valType)) {
                restrictions.putBundle(key, readBundleEntry(parser, values));
            } else if (ATTR_TYPE_BUNDLE_ARRAY.equals(valType)) {
                final int outerDepth = parser.getDepth();
                ArrayList<Bundle> bundleList = new ArrayList<>();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    Bundle childBundle = readBundleEntry(parser, values);
                    bundleList.add(childBundle);
                }
                restrictions.putParcelableArray(key,
                        bundleList.toArray(new Bundle[bundleList.size()]));
            } else {
                String value = parser.nextText().trim();
                if (ATTR_TYPE_BOOLEAN.equals(valType)) {
                    restrictions.putBoolean(key, Boolean.parseBoolean(value));
                } else if (ATTR_TYPE_INTEGER.equals(valType)) {
                    restrictions.putInt(key, Integer.parseInt(value));
                } else {
                    restrictions.putString(key, value);
                }
            }
        }
    }

    private static Bundle readBundleEntry(TypedXmlPullParser parser, ArrayList<String> values)
            throws IOException, XmlPullParserException {
        Bundle childBundle = new Bundle();
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            readEntry(childBundle, values, parser);
        }
        return childBundle;
    }

    @GuardedBy("mAppRestrictionsLock")
    private static void writeApplicationRestrictionsLAr(String packageName,
            Bundle restrictions, @UserIdInt int userId) {
        AtomicFile restrictionsFile = new AtomicFile(
                new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        writeApplicationRestrictionsLAr(restrictions, restrictionsFile);
    }

    @VisibleForTesting
    @GuardedBy("mAppRestrictionsLock")
    static void writeApplicationRestrictionsLAr(Bundle restrictions, AtomicFile restrictionsFile) {
        FileOutputStream fos = null;
        try {
            fos = restrictionsFile.startWrite();
            final TypedXmlSerializer serializer = Xml.resolveSerializer(fos);
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_RESTRICTIONS);
            writeBundle(restrictions, serializer);
            serializer.endTag(null, TAG_RESTRICTIONS);

            serializer.endDocument();
            restrictionsFile.finishWrite(fos);
        } catch (Exception e) {
            restrictionsFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing application restrictions list", e);
        }
    }

    private static void writeBundle(Bundle restrictions, TypedXmlSerializer serializer)
            throws IOException {
        for (String key : restrictions.keySet()) {
            Object value = restrictions.get(key);
            serializer.startTag(null, TAG_ENTRY);
            serializer.attribute(null, ATTR_KEY, key);

            if (value instanceof Boolean) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BOOLEAN);
                serializer.text(value.toString());
            } else if (value instanceof Integer) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_INTEGER);
                serializer.text(value.toString());
            } else if (value == null || value instanceof String) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_STRING);
                serializer.text(value != null ? (String) value : "");
            } else if (value instanceof Bundle) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE);
                writeBundle((Bundle) value, serializer);
            } else if (value instanceof Parcelable[]) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE_ARRAY);
                Parcelable[] array = (Parcelable[]) value;
                for (Parcelable parcelable : array) {
                    if (!(parcelable instanceof Bundle)) {
                        throw new IllegalArgumentException("bundle-array can only hold Bundles");
                    }
                    serializer.startTag(null, TAG_ENTRY);
                    serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE);
                    writeBundle((Bundle) parcelable, serializer);
                    serializer.endTag(null, TAG_ENTRY);
                }
            } else {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_STRING_ARRAY);
                String[] values = (String[]) value;
                serializer.attributeInt(null, ATTR_MULTIPLE, values.length);
                for (String choice : values) {
                    serializer.startTag(null, TAG_VALUE);
                    serializer.text(choice != null ? choice : "");
                    serializer.endTag(null, TAG_VALUE);
                }
            }
            serializer.endTag(null, TAG_ENTRY);
        }
    }

    @Override
    public int getUserSerialNumber(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null ? userInfo.serialNumber : -1;
        }
    }

    @Override
    public boolean isUserNameSet(@UserIdInt int userId) {
        if (!hasManageUsersOrPermission(android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED)) {
            throw new SecurityException("You need MANAGE_USERS or GET_ACCOUNTS_PRIVILEGED "
                    + "permissions to: get whether user name is set");
        }
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.name != null;
        }
    }

    @Override
    public int getUserHandle(int userSerialNumber) {
        synchronized (mUsersLock) {
            for (int userId : mUserIds) {
                UserInfo info = getUserInfoLU(userId);
                if (info != null && info.serialNumber == userSerialNumber) return userId;
            }
            // Not found
            return -1;
        }
    }

    @Override
    public long getUserCreationTime(@UserIdInt int userId) {
        int callingUserId = UserHandle.getCallingUserId();
        UserInfo userInfo = null;
        synchronized (mUsersLock) {
            if (callingUserId == userId) {
                userInfo = getUserInfoLU(userId);
            } else {
                UserInfo parent = getProfileParentLU(userId);
                if (parent != null && parent.id == callingUserId) {
                    userInfo = getUserInfoLU(userId);
                }
            }
        }
        if (userInfo == null) {
            throw new SecurityException("userId can only be the calling user or a "
                    + "profile associated with this user");
        }
        return userInfo.creationTime;
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private void updateUserIds() {
        int num = 0;
        int numIncludingPreCreated = 0;
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserInfo userInfo = mUsers.valueAt(i).info;
                if (!userInfo.partial) {
                    numIncludingPreCreated++;
                    if (!userInfo.preCreated) {
                        num++;
                    }
                }
            }
            if (DBG) {
                Slog.d(LOG_TAG, "updateUserIds(): numberUsers= " + num
                        + " includingPreCreated=" + numIncludingPreCreated);
            }
            final int[] newUsers = new int[num];
            final int[] newUsersIncludingPreCreated = new int[numIncludingPreCreated];

            int n = 0;
            int nIncludingPreCreated = 0;
            for (int i = 0; i < userSize; i++) {
                final UserInfo userInfo = mUsers.valueAt(i).info;
                if (!userInfo.partial) {
                    final int userId = mUsers.keyAt(i);
                    newUsersIncludingPreCreated[nIncludingPreCreated++] = userId;
                    if (!userInfo.preCreated) {
                        newUsers[n++] = userId;
                    }
                }
            }
            mUserIds = newUsers;
            mUserIdsIncludingPreCreated = newUsersIncludingPreCreated;
            if (DBG) {
                Slog.d(LOG_TAG, "updateUserIds(): userIds= " + Arrays.toString(mUserIds)
                        + " includingPreCreated=" + Arrays.toString(mUserIdsIncludingPreCreated));
            }
        }
    }

    /**
     * Called right before a user is started. This gives us a chance to prepare
     * app storage and apply any user restrictions.
     */
    public void onBeforeStartUser(@UserIdInt int userId) {
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("onBeforeStartUser-" + userId);
        final int userSerial = userInfo.serialNumber;
        // Migrate only if build fingerprints mismatch
        boolean migrateAppsData =
                !Build.VERSION.INCREMENTAL.equals(userInfo.lastLoggedInFingerprint);
        t.traceBegin("prepareUserData");
        mUserDataPreparer.prepareUserData(userId, userSerial, StorageManager.FLAG_STORAGE_DE);
        t.traceEnd();
        t.traceBegin("reconcileAppsData");
        mPm.reconcileAppsData(userId, StorageManager.FLAG_STORAGE_DE, migrateAppsData);
        t.traceEnd();

        if (userId != UserHandle.USER_SYSTEM) {
            t.traceBegin("applyUserRestrictions");
            synchronized (mRestrictionsLock) {
                applyUserRestrictionsLR(userId);
            }
            t.traceEnd();
        }
        t.traceEnd(); // onBeforeStartUser
    }

    /**
     * Called right before a user is unlocked. This gives us a chance to prepare
     * app storage.
     */
    public void onBeforeUnlockUser(@UserIdInt int userId) {
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        final int userSerial = userInfo.serialNumber;
        // Migrate only if build fingerprints mismatch
        boolean migrateAppsData =
                !Build.VERSION.INCREMENTAL.equals(userInfo.lastLoggedInFingerprint);
        mUserDataPreparer.prepareUserData(userId, userSerial, StorageManager.FLAG_STORAGE_CE);

        StorageManagerInternal smInternal = LocalServices.getService(StorageManagerInternal.class);
        smInternal.markCeStoragePrepared(userId);

        mPm.reconcileAppsData(userId, StorageManager.FLAG_STORAGE_CE, migrateAppsData);
    }

    /**
     * Examine all users present on given mounted volume, and destroy data
     * belonging to users that are no longer valid, or whose user ID has been
     * recycled.
     */
    void reconcileUsers(String volumeUuid) {
        mUserDataPreparer.reconcileUsers(volumeUuid, getUsers(
                /* excludePartial= */ true,
                /* excludeDying= */ true,
                /* excludePreCreated= */ false));
    }

    /**
     * Make a note of the last started time of a user and do some cleanup.
     * This is called with ActivityManagerService lock held.
     * @param userId the user that was just foregrounded
     */
    public void onUserLoggedIn(@UserIdInt int userId) {
        UserData userData = getUserDataNoChecks(userId);
        if (userData == null || userData.info.partial) {
            Slog.w(LOG_TAG, "userForeground: unknown user #" + userId);
            return;
        }

        final long now = System.currentTimeMillis();
        if (now > EPOCH_PLUS_30_YEARS) {
            userData.info.lastLoggedInTime = now;
        }
        userData.info.lastLoggedInFingerprint = Build.VERSION.INCREMENTAL;
        scheduleWriteUser(userData);
    }

    /**
     * Returns the next available user id, filling in any holes in the ids.
     */
    @VisibleForTesting
    int getNextAvailableId() {
        int nextId;
        synchronized (mUsersLock) {
            nextId = scanNextAvailableIdLocked();
            if (nextId >= 0) {
                return nextId;
            }
            // All ids up to MAX_USER_ID were used. Remove all mRemovingUserIds,
            // except most recently removed
            if (mRemovingUserIds.size() > 0) {
                Slog.i(LOG_TAG, "All available IDs are used. Recycling LRU ids.");
                mRemovingUserIds.clear();
                for (Integer recentlyRemovedId : mRecentlyRemovedIds) {
                    mRemovingUserIds.put(recentlyRemovedId, true);
                }
                nextId = scanNextAvailableIdLocked();
            }
        }
        if (nextId < 0) {
            throw new IllegalStateException("No user id available!");
        }
        return nextId;
    }

    @GuardedBy("mUsersLock")
    private int scanNextAvailableIdLocked() {
        for (int i = MIN_USER_ID; i < MAX_USER_ID; i++) {
            if (mUsers.indexOfKey(i) < 0 && !mRemovingUserIds.get(i)) {
                return i;
            }
        }
        return -1;
    }

    private static String packageToRestrictionsFileName(String packageName) {
        return RESTRICTIONS_FILE_PREFIX + packageName + XML_SUFFIX;
    }

    @Override
    public void setSeedAccountData(@UserIdInt int userId, String accountName, String accountType,
            PersistableBundle accountOptions, boolean persist) {
        checkManageUsersPermission("Require MANAGE_USERS permission to set user seed data");
        synchronized (mPackagesLock) {
            final UserData userData;
            synchronized (mUsersLock) {
                userData = getUserDataLU(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "No such user for settings seed data u=" + userId);
                    return;
                }
                userData.seedAccountName = accountName;
                userData.seedAccountType = accountType;
                userData.seedAccountOptions = accountOptions;
                userData.persistSeedData = persist;
            }
            if (persist) {
                writeUserLP(userData);
            }
        }
    }

    @Override
    public String getSeedAccountName() throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            return userData.seedAccountName;
        }
    }

    @Override
    public String getSeedAccountType() throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            return userData.seedAccountType;
        }
    }

    @Override
    public PersistableBundle getSeedAccountOptions() throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            return userData.seedAccountOptions;
        }
    }

    @Override
    public void clearSeedAccountData() throws RemoteException {
        checkManageUsersPermission("Cannot clear seed account information");
        synchronized (mPackagesLock) {
            UserData userData;
            synchronized (mUsersLock) {
                userData = getUserDataLU(UserHandle.getCallingUserId());
                if (userData == null) return;
                userData.clearSeedAccountData();
            }
            writeUserLP(userData);
        }
    }

    @Override
    public boolean someUserHasSeedAccount(String accountName, String accountType)
            throws RemoteException {
        checkManageUsersPermission("Cannot check seed account information");
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserData data = mUsers.valueAt(i);
                if (data.info.isInitialized()) continue;
                if (data.seedAccountName == null || !data.seedAccountName.equals(accountName)) {
                    continue;
                }
                if (data.seedAccountType == null || !data.seedAccountType.equals(accountType)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new Shell()).exec(this, in, out, err, args, callback, resultReceiver);
    }

    int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }

        final PrintWriter pw = shell.getOutPrintWriter();
        try {
            switch(cmd) {
                case "list":
                    return runList(pw, shell);
                case "report-system-user-package-whitelist-problems":
                    return runReportPackageWhitelistProblems(pw, shell);
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runList(PrintWriter pw, Shell shell) throws RemoteException {
        boolean all = false;
        boolean verbose = false;
        String opt;
        while ((opt = shell.getNextOption()) != null) {
            switch (opt) {
                case "-v":
                    verbose = true;
                    break;
                case "--all":
                    all = true;
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }
        final IActivityManager am = ActivityManager.getService();
        final List<UserInfo> users = getUsers(/* excludePartial= */ !all,
                /* excludingDying=*/ false, /* excludePreCreated= */ !all);
        if (users == null) {
            pw.println("Error: couldn't get users");
            return 1;
        } else {
            final int size = users.size();
            int currentUser = UserHandle.USER_NULL;
            if (verbose) {
                pw.printf("%d users:\n\n", size);
                currentUser = am.getCurrentUser().id;
            } else {
                // NOTE: the standard "list users" command is used by integration tests and
                // hence should not be changed. If you need to add more info, use the
                // verbose option.
                pw.println("Users:");
            }
            for (int i = 0; i < size; i++) {
                final UserInfo user = users.get(i);
                final boolean running = am.isUserRunning(user.id, 0);
                final boolean current = user.id == currentUser;
                final boolean hasParent = user.profileGroupId != user.id
                        && user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID;
                if (verbose) {
                    final DevicePolicyManagerInternal dpm = getDevicePolicyManagerInternal();
                    String deviceOwner = "";
                    String profileOwner = "";
                    if (dpm != null) {
                        final long ident = Binder.clearCallingIdentity();
                        // NOTE: dpm methods below CANNOT be called while holding the mUsersLock
                        try {
                            if (dpm.getDeviceOwnerUserId() == user.id) {
                                deviceOwner = " (device-owner)";
                            }
                            if (dpm.getProfileOwnerAsUser(user.id) != null) {
                                profileOwner = " (profile-owner)";
                            }
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                    pw.printf("%d: id=%d, name=%s, flags=%s%s%s%s%s%s%s%s%s\n", i, user.id,
                            user.name,
                            UserInfo.flagsToString(user.flags),
                            hasParent ? " (parentId=" + user.profileGroupId + ")" : "",
                            running ? " (running)" : "",
                            user.partial ? " (partial)" : "",
                            user.preCreated ? " (pre-created)" : "",
                            user.convertedFromPreCreated ? " (converted)" : "",
                            deviceOwner, profileOwner,
                            current ? " (current)" : "");
                } else {
                    // NOTE: the standard "list users" command is used by integration tests and
                    // hence should not be changed. If you need to add more info, use the
                    // verbose option.
                    pw.printf("\t%s%s\n", user, running ? " running" : "");
                }
            }
            return 0;
        }
    }

    private int runReportPackageWhitelistProblems(PrintWriter pw, Shell shell) {
        boolean verbose = false;
        boolean criticalOnly = false;
        int mode = UserSystemPackageInstaller.USER_TYPE_PACKAGE_WHITELIST_MODE_NONE;
        String opt;
        while ((opt = shell.getNextOption()) != null) {
            switch (opt) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "--critical-only":
                    criticalOnly = true;
                    break;
                case "--mode":
                    mode = Integer.parseInt(shell.getNextArgRequired());
                    break;
                default:
                    pw.println("Invalid option: " + opt);
                    return -1;
            }
        }

        Slog.d(LOG_TAG, "runReportPackageWhitelistProblems(): verbose=" + verbose
                + ", criticalOnly=" + criticalOnly
                + ", mode=" + UserSystemPackageInstaller.modeToString(mode));

        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ")) {
            mSystemPackageInstaller.dumpPackageWhitelistProblems(ipw, mode, verbose, criticalOnly);
        }
        return 0;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;

        long now = System.currentTimeMillis();
        final long nowRealtime = SystemClock.elapsedRealtime();

        final ActivityManagerInternal amInternal = LocalServices
                .getService(ActivityManagerInternal.class);
        pw.print("Current user: ");
        if (amInternal != null) {
            pw.println(amInternal.getCurrentUserId());
        } else {
            pw.println("N/A");
        }

        pw.println();
        StringBuilder sb = new StringBuilder();
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                pw.println("Users:");
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    if (userData == null) {
                        continue;
                    }
                    UserInfo userInfo = userData.info;
                    final int userId = userInfo.id;
                    pw.print("  "); pw.print(userInfo);
                    pw.print(" serialNo="); pw.print(userInfo.serialNumber);
                    pw.print(" isPrimary="); pw.print(userInfo.isPrimary());
                    if (userInfo.profileGroupId != userInfo.id
                            &&  userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                        pw.print(" parentId="); pw.print(userInfo.profileGroupId);
                    }

                    if (mRemovingUserIds.get(userId)) {
                        pw.print(" <removing> ");
                    }
                    if (userInfo.partial) {
                        pw.print(" <partial>");
                    }
                    if (userInfo.preCreated) {
                        pw.print(" <pre-created>");
                    }
                    if (userInfo.convertedFromPreCreated) {
                        pw.print(" <converted>");
                    }
                    pw.println();
                    pw.print("    Type: "); pw.println(userInfo.userType);
                    pw.print("    Flags: "); pw.print(userInfo.flags); pw.print(" (");
                    pw.print(UserInfo.flagsToString(userInfo.flags)); pw.println(")");
                    pw.print("    State: ");
                    final int state;
                    synchronized (mUserStates) {
                        state = mUserStates.get(userId, -1);
                    }
                    pw.println(UserState.stateToString(state));
                    pw.print("    Created: ");
                    dumpTimeAgo(pw, sb, now, userInfo.creationTime);

                    pw.print("    Last logged in: ");
                    dumpTimeAgo(pw, sb, now, userInfo.lastLoggedInTime);

                    pw.print("    Last logged in fingerprint: ");
                    pw.println(userInfo.lastLoggedInFingerprint);

                    pw.print("    Start time: ");
                    dumpTimeAgo(pw, sb, nowRealtime, userData.startRealtime);

                    pw.print("    Unlock time: ");
                    dumpTimeAgo(pw, sb, nowRealtime, userData.unlockRealtime);

                    pw.print("    Has profile owner: ");
                    pw.println(mIsUserManaged.get(userId));
                    pw.println("    Restrictions:");
                    synchronized (mRestrictionsLock) {
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mBaseUserRestrictions.getRestrictions(userInfo.id));
                        pw.println("    Device policy global restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ",
                                mDevicePolicyGlobalUserRestrictions.getRestrictions(userInfo.id));
                        pw.println("    Device policy local restrictions:");
                        getDevicePolicyLocalRestrictionsForTargetUserLR(
                                userInfo.id).dumpRestrictions(pw, "      ");
                        pw.println("    Effective restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ",
                                mCachedEffectiveUserRestrictions.getRestrictions(userInfo.id));
                    }

                    if (userData.account != null) {
                        pw.print("    Account name: " + userData.account);
                        pw.println();
                    }

                    if (userData.seedAccountName != null) {
                        pw.print("    Seed account name: " + userData.seedAccountName);
                        pw.println();
                        if (userData.seedAccountType != null) {
                            pw.print("         account type: " + userData.seedAccountType);
                            pw.println();
                        }
                        if (userData.seedAccountOptions != null) {
                            pw.print("         account options exist");
                            pw.println();
                        }
                    }

                    pw.println("    Ignore errors preparing storage: "
                            + userData.getIgnorePrepareStorageErrors());
                }
            }

            pw.println();
            pw.println("Device properties:");
            pw.println("  Device owner id:" + mDeviceOwnerUserId);
            pw.println();
            pw.println("  Guest restrictions:");
            synchronized (mGuestRestrictions) {
                UserRestrictionsUtils.dumpRestrictions(pw, "    ", mGuestRestrictions);
            }
            synchronized (mUsersLock) {
                pw.println();
                pw.println("  Device managed: " + mIsDeviceManaged);
                if (mRemovingUserIds.size() > 0) {
                    pw.println();
                    pw.println("  Recently removed userIds: " + mRecentlyRemovedIds);
                }
            }
            synchronized (mUserStates) {
                pw.print("  Started users state: [");
                final int size = mUserStates.states.size();
                for (int i = 0; i < size; i++) {
                    final int userId = mUserStates.states.keyAt(i);
                    final int state = mUserStates.states.valueAt(i);
                    pw.print(userId);
                    pw.print('=');
                    pw.print(UserState.stateToString(state));
                    if (i != size - 1) pw.print(", ");
                }
                pw.println(']');
            }
            synchronized (mUsersLock) {
                pw.print("  Cached user IDs: ");
                pw.println(Arrays.toString(mUserIds));
                pw.print("  Cached user IDs (including pre-created): ");
                pw.println(Arrays.toString(mUserIdsIncludingPreCreated));
            }

        } // synchronized (mPackagesLock)

        // Dump some capabilities
        pw.println();
        pw.print("  Max users: " + UserManager.getMaxSupportedUsers());
        pw.println(" (limit reached: " + isUserLimitReached() + ")");
        pw.println("  Supports switchable users: " + UserManager.supportsMultipleUsers());
        pw.println("  All guests ephemeral: " + Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_guestUserEphemeral));
        pw.println("  Force ephemeral users: " + mForceEphemeralUsers);
        pw.println("  Is split-system user: " + UserManager.isSplitSystemUser());
        pw.println("  Is headless-system mode: " + UserManager.isHeadlessSystemUserMode());
        pw.println("  User version: " + mUserVersion);
        pw.println("  Owner name: " + getOwnerName());
        if (DBG_ALLOCATION) {
            pw.println("  System user allocations: " + mUser0Allocations.get());
        }

        pw.println();
        pw.println("Number of listeners for");
        synchronized (mUserRestrictionsListeners) {
            pw.println("  restrictions: " + mUserRestrictionsListeners.size());
        }
        synchronized (mUserLifecycleListeners) {
            pw.println("  user lifecycle events: " + mUserLifecycleListeners.size());
        }

        // Dump UserTypes
        pw.println();
        pw.println("User types version: " + mUserTypeVersion);
        pw.println("User types (" + mUserTypes.size() + " types):");
        for (int i = 0; i < mUserTypes.size(); i++) {
            pw.println("    " + mUserTypes.keyAt(i) + ": ");
            mUserTypes.valueAt(i).dump(pw, "        ");
        }

        // TODO: create IndentingPrintWriter at the beginning of dump() and use the proper
        // indentation methods instead of explicit printing "  "
        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw)) {

            // Dump SystemPackageInstaller info
            ipw.println();
            mSystemPackageInstaller.dump(ipw);

            // NOTE: pw's not available after this point as it's auto-closed by ipw, so new dump
            // statements should use ipw below
        }
    }

    private static void dumpTimeAgo(PrintWriter pw, StringBuilder sb, long nowTime, long time) {
        if (time == 0) {
            pw.println("<unknown>");
        } else {
            sb.setLength(0);
            TimeUtils.formatDuration(nowTime - time, sb);
            sb.append(" ago");
            pw.println(sb);
        }
    }

    final class MainHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_USER_MSG:
                    removeMessages(WRITE_USER_MSG, msg.obj);
                    synchronized (mPackagesLock) {
                        int userId = ((UserData) msg.obj).info.id;
                        UserData userData = getUserDataNoChecks(userId);
                        if (userData != null) {
                            writeUserLP(userData);
                        } else {
                            Slog.i(LOG_TAG, "handle(WRITE_USER_MSG): no data for user " + userId
                                    + ", it was probably removed before handler could handle it");
                        }
                    }
            }
        }
    }

    /**
     * @param userId
     * @return whether the user has been initialized yet
     */
    boolean isUserInitialized(@UserIdInt int userId) {
        return mLocalService.isUserInitialized(userId);
    }

    private class LocalService extends UserManagerInternal {
        @Override
        public void setDevicePolicyUserRestrictions(@UserIdInt int originatingUserId,
                @NonNull Bundle global, @NonNull RestrictionsSet local,
                boolean isDeviceOwner) {
            UserManagerService.this.setDevicePolicyUserRestrictionsInner(originatingUserId,
                    global, local, isDeviceOwner);
        }

        @Override
        public Bundle getBaseUserRestrictions(@UserIdInt int userId) {
            synchronized (mRestrictionsLock) {
                return mBaseUserRestrictions.getRestrictions(userId);
            }
        }

        @Override
        public void setBaseUserRestrictionsByDpmsForMigration(
                @UserIdInt int userId, Bundle baseRestrictions) {
            synchronized (mRestrictionsLock) {
                if (mBaseUserRestrictions.updateRestrictions(userId,
                        new Bundle(baseRestrictions))) {
                    invalidateEffectiveUserRestrictionsLR(userId);
                }
            }

            final UserData userData = getUserDataNoChecks(userId);
            synchronized (mPackagesLock) {
                if (userData != null) {
                    writeUserLP(userData);
                } else {
                    Slog.w(LOG_TAG, "UserInfo not found for " + userId);
                }
            }
        }

        @Override
        public boolean getUserRestriction(@UserIdInt int userId, String key) {
            return getUserRestrictions(userId).getBoolean(key);
        }

        @Override
        public void addUserRestrictionsListener(UserRestrictionsListener listener) {
            synchronized (mUserRestrictionsListeners) {
                mUserRestrictionsListeners.add(listener);
            }
        }

        @Override
        public void removeUserRestrictionsListener(UserRestrictionsListener listener) {
            synchronized (mUserRestrictionsListeners) {
                mUserRestrictionsListeners.remove(listener);
            }
        }

        @Override
        public void addUserLifecycleListener(UserLifecycleListener listener) {
            synchronized (mUserLifecycleListeners) {
                mUserLifecycleListeners.add(listener);
            }
        }

        @Override
        public void removeUserLifecycleListener(UserLifecycleListener listener) {
            synchronized (mUserLifecycleListeners) {
                mUserLifecycleListeners.remove(listener);
            }
        }

        @Override
        public void setDeviceManaged(boolean isManaged) {
            synchronized (mUsersLock) {
                mIsDeviceManaged = isManaged;
            }
        }

        @Override
        public boolean isDeviceManaged() {
            synchronized (mUsersLock) {
                return mIsDeviceManaged;
            }
        }

        @Override
        public void setUserManaged(@UserIdInt int userId, boolean isManaged) {
            synchronized (mUsersLock) {
                mIsUserManaged.put(userId, isManaged);
            }
        }

        @Override
        public boolean isUserManaged(@UserIdInt int userId) {
            synchronized (mUsersLock) {
                return mIsUserManaged.get(userId);
            }
        }

        @Override
        public void setUserIcon(@UserIdInt int userId, Bitmap bitmap) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mPackagesLock) {
                    UserData userData = getUserDataNoChecks(userId);
                    if (userData == null || userData.info.partial) {
                        Slog.w(LOG_TAG, "setUserIcon: unknown user #" + userId);
                        return;
                    }
                    writeBitmapLP(userData.info, bitmap);
                    writeUserLP(userData);
                }
                sendUserInfoChangedBroadcast(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setForceEphemeralUsers(boolean forceEphemeralUsers) {
            synchronized (mUsersLock) {
                mForceEphemeralUsers = forceEphemeralUsers;
            }
        }

        @Override
        public void removeAllUsers() {
            if (UserHandle.USER_SYSTEM == ActivityManager.getCurrentUser()) {
                // Remove the non-system users straight away.
                removeNonSystemUsers();
            } else {
                // Switch to the system user first and then remove the other users.
                BroadcastReceiver userSwitchedReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int userId =
                                intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                        if (userId != UserHandle.USER_SYSTEM) {
                            return;
                        }
                        mContext.unregisterReceiver(this);
                        removeNonSystemUsers();
                    }
                };
                IntentFilter userSwitchedFilter = new IntentFilter();
                userSwitchedFilter.addAction(Intent.ACTION_USER_SWITCHED);
                mContext.registerReceiver(
                        userSwitchedReceiver, userSwitchedFilter, null, mHandler);

                // Switch to the system user.
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                am.switchUser(UserHandle.USER_SYSTEM);
            }
        }

        @Override
        public void onEphemeralUserStop(@UserIdInt int userId) {
            synchronized (mUsersLock) {
               UserInfo userInfo = getUserInfoLU(userId);
               if (userInfo != null && userInfo.isEphemeral()) {
                    // Do not allow switching back to the ephemeral user again as the user is going
                    // to be deleted.
                    userInfo.flags |= UserInfo.FLAG_DISABLED;
                    if (userInfo.isGuest()) {
                        // Indicate that the guest will be deleted after it stops.
                        userInfo.guestToRemove = true;
                    }
               }
            }
        }

        @Override
        public UserInfo createUserEvenWhenDisallowed(String name, @NonNull String userType,
                @UserInfoFlag int flags, String[] disallowedPackages, @Nullable Object token)
                throws UserManager.CheckedUserOperationException {
            return createUserInternalUnchecked(name, userType, flags,
                    UserHandle.USER_NULL, /* preCreated= */ false, disallowedPackages, token);
        }

        @Override
        public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
            return removeUserUnchecked(userId);
        }

        @Override
        public boolean isUserRunning(@UserIdInt int userId) {
            synchronized (mUserStates) {
                return mUserStates.get(userId, -1) >= 0;
            }
        }

        @Override
        public void setUserState(@UserIdInt int userId, int userState) {
            synchronized (mUserStates) {
                mUserStates.put(userId, userState);
            }
        }

        @Override
        public void removeUserState(@UserIdInt int userId) {
            synchronized (mUserStates) {
                mUserStates.delete(userId);
            }
        }

        @Override
        public int[] getUserIds() {
            return UserManagerService.this.getUserIds();
        }

        @Override
        public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
            return getUsers(/*excludePartial= */ true, excludeDying, /* excludePreCreated= */ true);
        }

        @Override
        public @NonNull List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying,
                boolean excludePreCreated) {
            return UserManagerService.this.getUsersInternal(excludePartial, excludeDying,
                    excludePreCreated);
        }

        @Override
        public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state = mUserStates.get(userId, -1);
            }
            // Special case, in the stopping/shutdown state user key can still be unlocked
            if (state == UserState.STATE_STOPPING || state == UserState.STATE_SHUTDOWN) {
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return (state == UserState.STATE_RUNNING_UNLOCKING)
                    || (state == UserState.STATE_RUNNING_UNLOCKED);
        }

        /**
         * The return values of this method are cached in clients.  If the
         * logic in this function changes then the cache invalidation code
         * may need to be revisited.
         */
        @Override
        public boolean isUserUnlocked(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state = mUserStates.get(userId, -1);
            }
            // Special case, in the stopping/shutdown state user key can still be unlocked
            if (state == UserState.STATE_STOPPING || state == UserState.STATE_SHUTDOWN) {
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return state == UserState.STATE_RUNNING_UNLOCKED;
        }

        @Override
        public boolean isUserInitialized(@UserIdInt int userId) {
            return (getUserInfo(userId).flags & UserInfo.FLAG_INITIALIZED) != 0;
        }

        @Override
        public boolean exists(@UserIdInt int userId) {
            return getUserInfoNoChecks(userId) != null;
        }

        @Override
        public boolean isProfileAccessible(int callingUserId, int targetUserId, String debugMsg,
                boolean throwSecurityException) {
            if (targetUserId == callingUserId) {
                return true;
            }
            synchronized (mUsersLock) {
                UserInfo callingUserInfo = getUserInfoLU(callingUserId);
                if (callingUserInfo == null || callingUserInfo.isProfile()) {
                    if (throwSecurityException) {
                        throw new SecurityException(
                                debugMsg + " for another profile "
                                        + targetUserId + " from " + callingUserId);
                    }
                    Slog.w(LOG_TAG, debugMsg + " for another profile "
                            + targetUserId + " from " + callingUserId);
                    return false;
                }

                UserInfo targetUserInfo = getUserInfoLU(targetUserId);
                if (targetUserInfo == null || !targetUserInfo.isEnabled()) {
                    // Do not throw any exception here as this could happen due to race conditions
                    // between the system updating its state and the client getting notified.
                    if (throwSecurityException) {
                        Slog.w(LOG_TAG, debugMsg + " for disabled profile "
                                + targetUserId + " from " + callingUserId);
                    }
                    return false;
                }

                if (targetUserInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID ||
                        targetUserInfo.profileGroupId != callingUserInfo.profileGroupId) {
                    if (throwSecurityException) {
                        throw new SecurityException(
                                debugMsg + " for unrelated profile " + targetUserId);
                    }
                    return false;
                }
            }
            return true;
        }

        @Override
        public int getProfileParentId(@UserIdInt int userId) {
            synchronized (mUsersLock) {
                UserInfo profileParent = getProfileParentLU(userId);
                if (profileParent == null) {
                    return userId;
                }
                return profileParent.id;
            }
        }

        @Override
        public boolean isSettingRestrictedForUser(String setting, @UserIdInt int userId,
                String value, int callingUid) {
            return UserManagerService.this.isSettingRestrictedForUser(setting, userId,
                    value, callingUid);
        }

        @Override
        public boolean hasUserRestriction(String restrictionKey, @UserIdInt int userId) {
            if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
                return false;
            }
            Bundle restrictions = getEffectiveUserRestrictions(userId);
            return restrictions != null && restrictions.getBoolean(restrictionKey);
        }

        @Override
        public @Nullable UserInfo getUserInfo(@UserIdInt int userId) {
            UserData userData;
            synchronized (mUsersLock) {
                userData = mUsers.get(userId);
            }
            return userData == null ? null : userData.info;
        }

        @Override
        public @NonNull UserInfo[] getUserInfos() {
            synchronized (mUsersLock) {
                int userSize = mUsers.size();
                UserInfo[] allInfos = new UserInfo[userSize];
                for (int i = 0; i < userSize; i++) {
                    allInfos[i] = mUsers.valueAt(i).info;
                }
                return allInfos;
            }
        }

        @Override
        public void setDefaultCrossProfileIntentFilters(
                @UserIdInt int parentUserId, @UserIdInt int profileUserId) {
            final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(profileUserId);
            final Bundle restrictions = getEffectiveUserRestrictions(profileUserId);
            UserManagerService.this.setDefaultCrossProfileIntentFilters(
                    profileUserId, userTypeDetails, restrictions, parentUserId);
        }

        @Override
        public boolean shouldIgnorePrepareStorageErrors(int userId) {
            synchronized (mUsersLock) {
                UserData userData = mUsers.get(userId);
                return userData != null && userData.getIgnorePrepareStorageErrors();
            }
        }
    }

    /**
     * Check if user has restrictions
     * @param restriction restrictions to check
     * @param userId id of the user
     *
     * @throws {@link android.os.UserManager.CheckedUserOperationException} if user has any of the
     *      specified restrictions
     */
    private void enforceUserRestriction(String restriction, @UserIdInt int userId, String message)
            throws UserManager.CheckedUserOperationException {
        if (hasUserRestriction(restriction, userId)) {
            String errorMessage = (message != null ? (message + ": ") : "")
                    + restriction + " is enabled.";
            Slog.w(LOG_TAG, errorMessage);
            throw new UserManager.CheckedUserOperationException(errorMessage,
                    UserManager.USER_OPERATION_ERROR_UNKNOWN);
        }
    }

    /**
     * Throws CheckedUserOperationException and shows error log
     * @param message message for exception and logging
     * @param userOperationResult result/error code
     * @throws UserManager.CheckedUserOperationException
     */
    private void throwCheckedUserOperationException(@NonNull String message,
            @UserManager.UserOperationResult int userOperationResult)
            throws UserManager.CheckedUserOperationException {
        Slog.e(LOG_TAG, message);
        throw new UserManager.CheckedUserOperationException(message, userOperationResult);
    }

    /* Remove all the users except of the system one. */
    private void removeNonSystemUsers() {
        ArrayList<UserInfo> usersToRemove = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.id != UserHandle.USER_SYSTEM) {
                    usersToRemove.add(ui);
                }
            }
        }
        for (UserInfo ui: usersToRemove) {
            removeUser(ui.id);
        }
    }

    private class Shell extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("User manager (user) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  list [-v] [-all]");
            pw.println("    Prints all users on the system.");
            pw.println("  report-system-user-package-whitelist-problems [-v | --verbose] "
                    + "[--critical-only] [--mode MODE]");
            pw.println("    Reports all issues on user-type package whitelist XML files. Options:");
            pw.println("    -v | --verbose : shows extra info, like number of issues");
            pw.println("    --critical-only: show only critical issues, excluding warnings");
            pw.println("    --mode MODE: shows what errors would be if device used mode MODE (where"
                    + " MODE is the whitelist mode integer as defined by "
                    + "config_userTypePackageWhitelistMode)");
        }
    }

    private static void debug(String message) {
        Slog.d(LOG_TAG, message
                + (DBG_WITH_STACKTRACE ? " called at\n" + Debug.getCallers(10, "  ") : ""));
    }

    /** @see #getMaxUsersOfTypePerParent(UserTypeDetails) */
    @VisibleForTesting
    int getMaxUsersOfTypePerParent(String userType) {
        final UserTypeDetails type = mUserTypes.get(userType);
        if (type == null) {
            return 0;
        }
        return getMaxUsersOfTypePerParent(type);
    }

    /**
     * Returns the maximum number of users allowed for the given userTypeDetails per parent user.
     * This is applicable for user types that are {@link UserTypeDetails#isProfile()}.
     * If there is no maximum, {@link UserTypeDetails#UNLIMITED_NUMBER_OF_USERS} is returned.
     * Under certain circumstances (such as after a change-user-type) the max value can actually
     * be exceeded: this is allowed in order to keep the device in a usable state.
     * An error is logged in {@link UserManagerService#upgradeProfileToTypeLU}
     */
    private static int getMaxUsersOfTypePerParent(UserTypeDetails userTypeDetails) {
        final int defaultMax = userTypeDetails.getMaxAllowedPerParent();
        if (!Build.IS_ENG) {
            return defaultMax;
        } else {
            if (userTypeDetails.isManagedProfile()) {
                return SystemProperties.getInt("persist.sys.max_profiles", defaultMax);
            }
        }
        return defaultMax;
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
    int getFreeProfileBadgeLU(int parentUserId, String userType) {
        Set<Integer> usedBadges = new ArraySet<>();
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo ui = mUsers.valueAt(i).info;
            // Check which badge indexes are already used by this profile group.
            if (ui.userType.equals(userType)
                    && ui.profileGroupId == parentUserId
                    && !mRemovingUserIds.get(ui.id)) {
                usedBadges.add(ui.profileBadge);
            }
        }
        int maxUsersOfType = getMaxUsersOfTypePerParent(userType);
        if (maxUsersOfType == UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
            maxUsersOfType = Integer.MAX_VALUE;
        }
        for (int i = 0; i < maxUsersOfType; i++) {
            if (!usedBadges.contains(i)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Checks if the given user has a managed profile associated with it.
     * @param userId The parent user
     * @return
     */
    boolean hasManagedProfile(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = mUsers.valueAt(i).info;
                if (userId != profile.id && isProfileOf(userInfo, profile)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Checks if the calling package name matches with the calling UID, throw
     * {@link SecurityException} if not.
     */
    private void verifyCallingPackage(String callingPackage, int callingUid) {
        int packageUid = mPm.getPackageUid(callingPackage, 0,  UserHandle.getUserId(callingUid));
        if (packageUid != callingUid) {
            throw new SecurityException("Specified package " + callingPackage
                    + " does not match the calling uid " + callingUid);
        }
    }

    /** Retrieves the internal package manager interface. */
    private PackageManagerInternal getPackageManagerInternal() {
        // Don't need to synchonize; worst-case scenario LocalServices will be called twice.
        if (mPmInternal == null) {
            mPmInternal = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPmInternal;
    }

    /** Returns the internal device policy manager interface. */
    private DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
        if (mDevicePolicyManagerInternal == null) {
            mDevicePolicyManagerInternal =
                    LocalServices.getService(DevicePolicyManagerInternal.class);
        }
        return mDevicePolicyManagerInternal;
    }
}
