/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.rollback;

import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.os.storage.StorageManager;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encapsulates the logic for initiating userdata snapshots and rollbacks via installd.
 */
@VisibleForTesting
// TODO(narayan): Reason about the failure scenarios that involve one or more IPCs to installd
// failing. We need to decide what course of action to take if calls to snapshotAppData or
// restoreAppDataSnapshot fail.
public class AppDataRollbackHelper {
    private static final String TAG = "RollbackManager";

    private final Installer mInstaller;

    public AppDataRollbackHelper(Installer installer) {
        mInstaller = installer;
    }

    /**
     * Creates an app data snapshot for a specified {@code packageRollbackInfo} and the specified
     * {@code userIds}. Updates said {@code packageRollbackInfo} with the inodes of the CE user data
     * snapshot folders.
     */
    public void snapshotAppData(
            int snapshotId, PackageRollbackInfo packageRollbackInfo, int[] userIds) {
        for (int user : userIds) {
            final int storageFlags;
            if (isUserCredentialLocked(user)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't copy
                // across app user data until the user unlocks their device.
                Slog.v(TAG, "User: " + user + " isn't unlocked, skipping CE userdata backup.");
                storageFlags = Installer.FLAG_STORAGE_DE;
                packageRollbackInfo.addPendingBackup(user);
            } else {
                storageFlags = Installer.FLAG_STORAGE_CE | Installer.FLAG_STORAGE_DE;
            }

            try {
                long ceSnapshotInode = mInstaller.snapshotAppData(
                        packageRollbackInfo.getPackageName(), user, snapshotId, storageFlags);
                if ((storageFlags & Installer.FLAG_STORAGE_CE) != 0) {
                    packageRollbackInfo.putCeSnapshotInode(user, ceSnapshotInode);
                }
            } catch (InstallerException ie) {
                Slog.e(TAG, "Unable to create app data snapshot for: "
                        + packageRollbackInfo.getPackageName() + ", userId: " + user, ie);
            }
        }
        packageRollbackInfo.getSnapshottedUsers().addAll(IntArray.wrap(userIds));
    }

    /**
     * Restores an app data snapshot for a specified {@code packageRollbackInfo}, for a specified
     * {@code userId}.
     *
     * @return {@code true} iff. a change to the {@code packageRollbackInfo} has been made. Changes
     *         to {@code packageRollbackInfo} are restricted to the removal or addition of {@code
     *         userId} to the list of pending backups or restores.
     */
    public boolean restoreAppData(int rollbackId, PackageRollbackInfo packageRollbackInfo,
            int userId, int appId, String seInfo) {
        int storageFlags = Installer.FLAG_STORAGE_DE;

        final IntArray pendingBackups = packageRollbackInfo.getPendingBackups();
        final List<RestoreInfo> pendingRestores = packageRollbackInfo.getPendingRestores();
        boolean changedRollback = false;

        // If we still have a userdata backup pending for this user, it implies that the user
        // hasn't unlocked their device between the point of backup and the point of restore,
        // so the data cannot have changed. We simply skip restoring CE data in this case.
        if (pendingBackups != null && pendingBackups.indexOf(userId) != -1) {
            pendingBackups.remove(pendingBackups.indexOf(userId));
            changedRollback = true;
        } else {
            // There's no pending CE backup for this user, which means that we successfully
            // managed to backup data for the user, which means we seek to restore it
            if (isUserCredentialLocked(userId)) {
                // We've encountered a user that hasn't unlocked on a FBE device, so we can't
                // copy across app user data until the user unlocks their device.
                pendingRestores.add(new RestoreInfo(userId, appId, seInfo));
                changedRollback = true;
            } else {
                // This user has unlocked, we can proceed to restore both CE and DE data.
                storageFlags = storageFlags | Installer.FLAG_STORAGE_CE;
            }
        }

        try {
            mInstaller.restoreAppDataSnapshot(packageRollbackInfo.getPackageName(), appId, seInfo,
                    userId, rollbackId, storageFlags);
        } catch (InstallerException ie) {
            Slog.e(TAG, "Unable to restore app data snapshot: "
                        + packageRollbackInfo.getPackageName(), ie);
        }

        return changedRollback;
    }

    /**
     * Deletes an app data snapshot with a given {@code rollbackId} for a specified package
     * {@code packageName} for a given {@code user}.
     */
    public void destroyAppDataSnapshot(int rollbackId, PackageRollbackInfo packageRollbackInfo,
            int user) {
        int storageFlags = Installer.FLAG_STORAGE_DE;
        final SparseLongArray ceSnapshotInodes = packageRollbackInfo.getCeSnapshotInodes();
        long ceSnapshotInode = ceSnapshotInodes.get(user);
        if (ceSnapshotInode > 0) {
            storageFlags |= Installer.FLAG_STORAGE_CE;
        }
        try {
            mInstaller.destroyAppDataSnapshot(packageRollbackInfo.getPackageName(), user,
                    ceSnapshotInode, rollbackId, storageFlags);
            if ((storageFlags & Installer.FLAG_STORAGE_CE) != 0) {
                ceSnapshotInodes.delete(user);
            }
        } catch (InstallerException ie) {
            Slog.e(TAG, "Unable to delete app data snapshot for "
                        + packageRollbackInfo.getPackageName(), ie);
        }
    }

    /**
     * Computes the list of pending backups for {@code userId} given lists of rollbacks.
     * Packages pending backup for the given user are added to {@code pendingBackupPackages} along
     * with their corresponding {@code PackageRollbackInfo}.
     *
     * @return the list of rollbacks that have pending backups. Note that some of the
     *         backups won't be performed, because they might be counteracted by pending restores.
     */
    private static List<Rollback> computePendingBackups(int userId,
            Map<String, PackageRollbackInfo> pendingBackupPackages,
            List<Rollback> rollbacks) {
        List<Rollback> rollbacksWithPendingBackups = new ArrayList<>();

        for (Rollback rollback : rollbacks) {
            for (PackageRollbackInfo info : rollback.info.getPackages()) {
                final IntArray pendingBackupUsers = info.getPendingBackups();
                if (pendingBackupUsers != null) {
                    final int idx = pendingBackupUsers.indexOf(userId);
                    if (idx != -1) {
                        pendingBackupPackages.put(info.getPackageName(), info);
                        if (rollbacksWithPendingBackups.indexOf(rollback) == -1) {
                            rollbacksWithPendingBackups.add(rollback);
                        }
                    }
                }
            }
        }
        return rollbacksWithPendingBackups;
    }

    /**
     * Computes the list of pending restores for {@code userId} given lists of rollbacks.
     * Packages pending restore are added to {@code pendingRestores} along with their corresponding
     * {@code PackageRollbackInfo}.
     *
     * @return the list of rollbacks that have pending restores. Note that some of the
     *         restores won't be performed, because they might be counteracted by pending backups.
     */
    private static List<Rollback> computePendingRestores(int userId,
            Map<String, PackageRollbackInfo> pendingRestorePackages,
            List<Rollback> rollbacks) {
        List<Rollback> rollbacksWithPendingRestores = new ArrayList<>();

        for (Rollback rollback : rollbacks) {
            for (PackageRollbackInfo info : rollback.info.getPackages()) {
                final RestoreInfo ri = info.getRestoreInfo(userId);
                if (ri != null) {
                    pendingRestorePackages.put(info.getPackageName(), info);
                    if (rollbacksWithPendingRestores.indexOf(rollback) == -1) {
                        rollbacksWithPendingRestores.add(rollback);
                    }
                }
            }
        }

        return rollbacksWithPendingRestores;
    }

    /**
     * Commits the list of pending backups and restores for a given {@code userId}. For rollbacks
     * with pending backups, updates the {@code Rollback} instance with a mapping from
     * {@code userId} to inode of the CE user data snapshot.
     *
     * @return the set of rollbacks with changes that should be stored on disk.
     */
    public Set<Rollback> commitPendingBackupAndRestoreForUser(int userId,
            List<Rollback> rollbacks) {

        final Map<String, PackageRollbackInfo> pendingBackupPackages = new HashMap<>();
        final List<Rollback> pendingBackups = computePendingBackups(userId,
                pendingBackupPackages, rollbacks);

        final Map<String, PackageRollbackInfo> pendingRestorePackages = new HashMap<>();
        final List<Rollback> pendingRestores = computePendingRestores(userId,
                pendingRestorePackages, rollbacks);

        // First remove unnecessary backups, i.e. when user did not unlock their phone between the
        // request to backup data and the request to restore it.
        Iterator<Map.Entry<String, PackageRollbackInfo>> iter =
                pendingBackupPackages.entrySet().iterator();
        while (iter.hasNext()) {
            PackageRollbackInfo backupPackage = iter.next().getValue();
            PackageRollbackInfo restorePackage =
                    pendingRestorePackages.get(backupPackage.getPackageName());
            if (restorePackage != null) {
                backupPackage.removePendingBackup(userId);
                backupPackage.removePendingRestoreInfo(userId);
                iter.remove();
                pendingRestorePackages.remove(backupPackage.getPackageName());
            }
        }

        if (!pendingBackupPackages.isEmpty()) {
            for (Rollback rollback : pendingBackups) {
                for (PackageRollbackInfo info : rollback.info.getPackages()) {
                    final IntArray pendingBackupUsers = info.getPendingBackups();
                    final int idx = pendingBackupUsers.indexOf(userId);
                    if (idx != -1) {
                        try {
                            long ceSnapshotInode = mInstaller.snapshotAppData(info.getPackageName(),
                                    userId, rollback.info.getRollbackId(),
                                    Installer.FLAG_STORAGE_CE);
                            info.putCeSnapshotInode(userId, ceSnapshotInode);
                            pendingBackupUsers.remove(idx);
                        } catch (InstallerException ie) {
                            Slog.e(TAG,
                                    "Unable to create app data snapshot for: "
                                    + info.getPackageName() + ", userId: " + userId, ie);
                        }
                    }
                }
            }
        }

        if (!pendingRestorePackages.isEmpty()) {
            for (Rollback rollback : pendingRestores) {
                for (PackageRollbackInfo info : rollback.info.getPackages()) {
                    final RestoreInfo ri = info.getRestoreInfo(userId);
                    if (ri != null) {
                        try {
                            mInstaller.restoreAppDataSnapshot(info.getPackageName(), ri.appId,
                                    ri.seInfo, userId, rollback.info.getRollbackId(),
                                    Installer.FLAG_STORAGE_CE);
                            info.removeRestoreInfo(ri);
                        } catch (InstallerException ie) {
                            Slog.e(TAG, "Unable to restore app data snapshot for: "
                                    + info.getPackageName(), ie);
                        }
                    }
                }
            }
        }

        final Set<Rollback> changed = new HashSet<>(pendingBackups);
        changed.addAll(pendingRestores);
        return changed;
    }

    /**
     * @return {@code true} iff. {@code userId} is locked on an FBE device.
     */
    @VisibleForTesting
    public boolean isUserCredentialLocked(int userId) {
        return StorageManager.isFileEncryptedNativeOrEmulated()
                && !StorageManager.isUserKeyUnlocked(userId);
    }
}
