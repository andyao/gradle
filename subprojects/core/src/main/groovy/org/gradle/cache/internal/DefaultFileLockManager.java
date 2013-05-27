/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.cache.internal;

import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.util.GFileUtils;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Uses file system locks on a lock file per target file. Each lock file is made up of 2 regions:
 *
 * <ul> <li>State region: 1 byte version field, 1 byte clean flag.</li> <li>Owner information region: 1 byte version field, utf-8 encoded owner process id, utf-8 encoded owner operation display
 * name.</li> </ul>
 */
public class DefaultFileLockManager implements FileLockManager {
    private static final Logger LOGGER = Logging.getLogger(DefaultFileLockManager.class);
    private static final int DEFAULT_LOCK_TIMEOUT = 60000;
    private static final byte STATE_REGION_PROTOCOL = 1;
    private static final int STATE_REGION_SIZE = 2;
    private static final int STATE_REGION_POS = 0;
    private static final byte INFORMATION_REGION_PROTOCOL = 2;
    private static final int INFORMATION_REGION_POS = STATE_REGION_POS + STATE_REGION_SIZE;
    public static final int INFORMATION_REGION_SIZE = 2048;
    public static final int INFORMATION_REGION_DESCR_CHUNK_LIMIT = 340;
    private final ProcessMetaDataProvider metaDataProvider;
    private final int lockTimeoutMs;

    public DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider) {
        this(metaDataProvider, DEFAULT_LOCK_TIMEOUT);
    }

    public DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider, int lockTimeoutMs) {
        this.metaDataProvider = metaDataProvider;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public FileLock lock(File target, LockMode mode, String targetDisplayName, int port) throws LockTimeoutException {
        return lock(target, mode, targetDisplayName, "", port);
    }

    public FileLock lock(File target, LockMode mode, String targetDisplayName, String operationDisplayName, int port) {
        if (mode == LockMode.None) {
            throw new UnsupportedOperationException(String.format("No %s mode lock implementation available.", mode));
        }
        File canonicalTarget = GFileUtils.canonicalise(target);

        try {
            return new DefaultFileLock(canonicalTarget, mode, targetDisplayName, operationDisplayName, port);
        } catch (Throwable t) {
            throw UncheckedException.throwAsUncheckedException(t);
        }
    }

    private class DefaultFileLock extends AbstractFileAccess implements FileLock {
        private final File lockFile;
        private final File target;
        private final LockMode mode;
        private final String displayName;
        private final String operationDisplayName;
        private java.nio.channels.FileLock lock;
        private RandomAccessFile lockFileAccess;
        private boolean integrityViolated;
        private int port;
        private boolean contended;
        private boolean busy;

        public DefaultFileLock(File target, LockMode mode, String displayName, String operationDisplayName, int port) throws Throwable {
            this.port = port;
            if (mode == LockMode.None) {
                throw new UnsupportedOperationException("Locking mode None is not supported.");
            }

            this.target = target;

            this.displayName = displayName;
            this.operationDisplayName = operationDisplayName;
            if (target.isDirectory()) {
                lockFile = new File(target, target.getName() + ".lock");
            } else {
                lockFile = new File(target.getParentFile(), target.getName() + ".lock");
            }

            GFileUtils.mkdirs(lockFile.getParentFile());
            lockFile.createNewFile();
            lockFileAccess = new RandomAccessFile(lockFile, "rw");
            try {
                lock = lock(mode);
                integrityViolated = !getUnlockedCleanly();
            } catch (Throwable t) {
                // Also releases any locks
                lockFileAccess.close();
                throw t;
            }

            this.mode = lock.isShared() ? LockMode.Shared : LockMode.Exclusive;
        }

        public boolean isLockFile(File file) {
            return file.equals(lockFile);
        }

        public boolean getUnlockedCleanly() {
            assertOpen();
            try {
                lockFileAccess.seek(STATE_REGION_POS + 1);
                if (!lockFileAccess.readBoolean()) {
                    // Process has crashed while updating target file
                    return false;
                }
            } catch (EOFException e) {
                // Process has crashed writing to lock file
                return false;
            } catch (Exception e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }

            return true;
        }

        public <T> T readFile(Factory<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException {
            assertOpenAndIntegral();
            return action.create();
        }

        public void updateFile(Runnable action) throws LockTimeoutException, FileIntegrityViolationException {
            assertOpenAndIntegral();
            doWriteAction(action);
        }

        public void writeFile(Runnable action) throws LockTimeoutException {
            assertOpen();
            doWriteAction(action);
        }

        private void doWriteAction(Runnable action) {
            if (mode != LockMode.Exclusive) {
                throw new InsufficientLockModeException("An exclusive lock is required for this operation");
            }

            try {
                integrityViolated = true;
                markDirty();
                action.run();
                markClean();
                integrityViolated = false;
            } catch (Throwable t) {
                throw UncheckedException.throwAsUncheckedException(t);
            }
        }

        private void assertOpen() {
            if (lock == null) {
                throw new IllegalStateException("This lock has been closed.");
            }
        }

        private void assertOpenAndIntegral() {
            assertOpen();
            if (integrityViolated) {
                throw new FileIntegrityViolationException(String.format("The file '%s' was not unlocked cleanly", target));
            }
        }

        private void markClean() throws IOException {
            lockFileAccess.seek(STATE_REGION_POS);
            lockFileAccess.writeByte(STATE_REGION_PROTOCOL);
            lockFileAccess.writeBoolean(true);
            assert lockFileAccess.getFilePointer() == STATE_REGION_SIZE + STATE_REGION_POS;
        }

        private void markDirty() throws IOException {
            lockFileAccess.seek(STATE_REGION_POS);
            lockFileAccess.writeByte(STATE_REGION_PROTOCOL);
            lockFileAccess.writeBoolean(false);
            assert lockFileAccess.getFilePointer() == STATE_REGION_SIZE + STATE_REGION_POS;
        }

        public void close() {
            if (lockFileAccess == null) {
                return;
            }
            try {
                LOGGER.debug("Releasing lock on {}.", displayName);
                // Also releases any locks
                try {
                    if (lock != null && !lock.isShared()) {
                        // Discard information region
                        lockFileAccess.setLength(INFORMATION_REGION_POS);
                    }
                } finally {
                    lockFileAccess.close();
                }
            } catch (IOException e) {
                LOGGER.warn("Error releasing lock on {}: {}", displayName, e);
            } finally {
                lock = null;
                lockFileAccess = null;
            }
        }

        public LockMode getMode() {
            return mode;
        }

        private java.nio.channels.FileLock lock(FileLockManager.LockMode lockMode) throws Throwable {
            LOGGER.debug("Waiting to acquire {} lock on {}.", lockMode.toString().toLowerCase(), displayName);
            long timeout = System.currentTimeMillis() + lockTimeoutMs;

            // Lock the state region, with the requested mode
            java.nio.channels.FileLock stateRegionLock = lockStateRegion(lockMode, timeout);
            if (stateRegionLock == null) {
                String ownerAddress = readInformationRegion(timeout);
                throw new LockTimeoutException(String.format("Timeout waiting to lock %s. It is currently in use by another Gradle instance.%nOwner address: %s%nOur PID: %s%nOur operation: %s%nLock file: %s",
                        displayName, ownerAddress, metaDataProvider.getProcessIdentifier(), operationDisplayName, lockFile));
            }

            try {
                if (lockFileAccess.length() > 0) {
                    lockFileAccess.seek(STATE_REGION_POS);
                    if (lockFileAccess.readByte() != STATE_REGION_PROTOCOL) {
                        throw new IllegalStateException(String.format("Unexpected lock protocol found in lock file '%s' for %s.", lockFile, displayName));
                    }
                }

                if (!stateRegionLock.isShared()) {
                    // We have an exclusive lock (whether we asked for it or not).
                    // Update the state region
                    if (lockFileAccess.length() < STATE_REGION_SIZE) {
                        // File did not exist before locking
                        lockFileAccess.seek(STATE_REGION_POS);
                        lockFileAccess.writeByte(STATE_REGION_PROTOCOL);
                        lockFileAccess.writeBoolean(false);
                    }
                    // Acquire an exclusive lock on the information region and write our details there
                    java.nio.channels.FileLock informationRegionLock = lockInformationRegion(LockMode.Exclusive, timeout);
                    if (informationRegionLock == null) {
                        throw new IllegalStateException(String.format("Timeout waiting to lock the information region for lock %s", displayName));
                    }
                    // check that the length of the reserved region is enough for storing our content
                    try {
                        lockFileAccess.seek(INFORMATION_REGION_POS);
                        lockFileAccess.writeByte(INFORMATION_REGION_PROTOCOL);
                        lockFileAccess.writeUTF(trimIfNecessary(metaDataProvider.getProcessIdentifier()));
                        lockFileAccess.writeUTF(trimIfNecessary(port + ""));
                        lockFileAccess.setLength(lockFileAccess.getFilePointer());
                    } finally {
                        informationRegionLock.release();
                    }
                }
            } catch (Throwable t) {
                stateRegionLock.release();
                throw t;
            }

            LOGGER.debug("Lock acquired.");
            return stateRegionLock;
        }

        private String readInformationRegion(long timeout) throws IOException, InterruptedException {
            // Can't acquire lock, get details of owner to include in the error message
            String ownerPid = "unknown";
            String ownerAddress = "unknown";
            java.nio.channels.FileLock informationRegionLock = lockInformationRegion(LockMode.Shared, timeout);
            if (informationRegionLock == null) {
                LOGGER.debug("Could not lock information region for {}. Ignoring.", displayName);
            } else {
                try {
                    if (lockFileAccess.length() <= INFORMATION_REGION_POS) {
                        LOGGER.debug("Lock file for {} is too short to contain information region. Ignoring.", displayName);
                    } else {
                        lockFileAccess.seek(INFORMATION_REGION_POS);
                        if (lockFileAccess.readByte() != INFORMATION_REGION_PROTOCOL) {
                            throw new IllegalStateException(String.format("Unexpected lock protocol found in lock file '%s' for %s.", lockFile, displayName));
                        }
                        ownerPid = lockFileAccess.readUTF();
                        ownerAddress = lockFileAccess.readUTF();
                    }
                } finally {
                    informationRegionLock.release();
                }
            }
            return ownerAddress;
        }

        private String trimIfNecessary(String inputString) {
            if(inputString.length() > INFORMATION_REGION_DESCR_CHUNK_LIMIT){
                return inputString.substring(0, INFORMATION_REGION_DESCR_CHUNK_LIMIT);
            }else{
                return inputString;
            }
        }

        private java.nio.channels.FileLock lockStateRegion(LockMode lockMode, final long timeout) throws IOException, InterruptedException {
            return lockRegion(lockMode, timeout, STATE_REGION_POS, STATE_REGION_SIZE, new Runnable() {
                public void run() {
                    try {
                        String ownerPort = readInformationRegion(timeout);
                        LOGGER.lifecycle("Will attempt to ping owner at {}", ownerPort);
                        if (!"unknown".equals(ownerPort)) {
                            FileLockCommunicator.pingOwner(ownerPort, target);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        private java.nio.channels.FileLock lockInformationRegion(LockMode lockMode, long timeout) throws IOException, InterruptedException {
            return lockRegion(lockMode, timeout, INFORMATION_REGION_POS, INFORMATION_REGION_SIZE - INFORMATION_REGION_POS, new Runnable() {
                public void run() {
                    //no op
                }
            });
        }

        private java.nio.channels.FileLock lockRegion(FileLockManager.LockMode lockMode, long timeout,
                    long start, long size, Runnable failAction) throws IOException, InterruptedException {
            do {
                java.nio.channels.FileLock fileLock = lockFileAccess.getChannel().tryLock(start, size, lockMode == LockMode.Shared);
                if (fileLock != null) {
                    return fileLock;
                }
                failAction.run();
                Thread.sleep(200L);
            } while (System.currentTimeMillis() < timeout);
            return null;
        }

        public void setContended(boolean contended) {
            this.contended = true;
        }

        public boolean isContended() {
            return contended;
        }

        public void setBusy(boolean busy) {
            this.busy = busy;
        }

        public boolean isBusy() {
            return busy;
        }
    }
}