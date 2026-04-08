/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.io.nativeio;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.HardLink;
import org.apache.hadoop.fs.PathIOException;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.SecureIOUtils.AlreadyExistsException;
import org.apache.hadoop.util.CleanerUtil;
import org.apache.hadoop.util.NativeCodeLoader;
import org.apache.hadoop.util.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JNI wrappers for various native IO-related calls not available in Java.
 * This replacement intentionally disables JNI-backed behavior on Windows and
 * uses best-effort Java/NIO implementations instead.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class NativeIO {
    private static final Logger LOG = LoggerFactory.getLogger(NativeIO.class);
    private static final Map<FileDescriptor, Closeable> OPEN_HANDLES =
            new ConcurrentHashMap<FileDescriptor, Closeable>();

    private static boolean workaroundNonThreadSafePasswdCalls = false;
    private static long cacheTimeout;
    private static boolean initialized = false;
    private static final boolean nativeLoaded = false;

    public static class POSIX {
        public static int O_RDONLY = 0x0000;
        public static int O_WRONLY = 0x0001;
        public static int O_RDWR = 0x0002;
        public static int O_CREAT = 0x0040;
        public static int O_EXCL = 0x0080;
        public static int O_NOCTTY = 0x0100;
        public static int O_TRUNC = 0x0200;
        public static int O_APPEND = 0x0400;
        public static int O_NONBLOCK = 0x0800;
        public static int O_SYNC = 0x1000;

        public static int POSIX_FADV_NORMAL = 0;
        public static int POSIX_FADV_RANDOM = 1;
        public static int POSIX_FADV_SEQUENTIAL = 2;
        public static int POSIX_FADV_WILLNEED = 3;
        public static int POSIX_FADV_DONTNEED = 4;
        public static int POSIX_FADV_NOREUSE = 5;

        public static int SYNC_FILE_RANGE_WAIT_BEFORE = 1;
        public static int SYNC_FILE_RANGE_WRITE = 2;
        public static int SYNC_FILE_RANGE_WAIT_AFTER = 4;

        private static final Logger LOG = LoggerFactory.getLogger(NativeIO.class);
        public static boolean fadvisePossible = false;

        static final String WORKAROUND_NON_THREADSAFE_CALLS_KEY =
                "hadoop.workaround.non.threadsafe.getpwuid";
        static final boolean WORKAROUND_NON_THREADSAFE_CALLS_DEFAULT = true;

        private static CacheManipulator cacheManipulator = new CacheManipulator();

        static {
            workaroundNonThreadSafePasswdCalls = new Configuration().getBoolean(
                    WORKAROUND_NON_THREADSAFE_CALLS_KEY,
                    WORKAROUND_NON_THREADSAFE_CALLS_DEFAULT);
        }

        public static CacheManipulator getCacheManipulator() {
            return cacheManipulator;
        }

        public static void setCacheManipulator(CacheManipulator cacheManipulator) {
            POSIX.cacheManipulator = cacheManipulator;
        }

        @VisibleForTesting
        public static class CacheManipulator {
            public void mlock(String identifier, ByteBuffer buffer, long len)
                    throws IOException {
                POSIX.mlock(buffer, len);
            }

            public long getMemlockLimit() {
                return NativeIO.getMemlockLimit();
            }

            public long getOperatingSystemPageSize() {
                return NativeIO.getOperatingSystemPageSize();
            }

            public void posixFadviseIfPossible(String identifier,
                                               FileDescriptor fd, long offset, long len, int flags)
                    throws NativeIOException {
                NativeIO.POSIX.posixFadviseIfPossible(identifier, fd, offset, len, flags);
            }

            public boolean verifyCanMlock() {
                return NativeIO.isAvailable();
            }
        }

        @VisibleForTesting
        public static class NoMlockCacheManipulator extends CacheManipulator {
            public void mlock(String identifier, ByteBuffer buffer, long len) {
                LOG.info("mlocking {}", identifier);
            }

            public long getMemlockLimit() {
                return 1125899906842624L;
            }

            public long getOperatingSystemPageSize() {
                return 4096;
            }

            public boolean verifyCanMlock() {
                return true;
            }
        }

        public static boolean isAvailable() {
            return false;
        }

        public static FileDescriptor open(String path, int flags, int mode)
                throws IOException {
            Path nioPath = new File(path).toPath();
            boolean write = (flags & O_WRONLY) != 0 || (flags & O_RDWR) != 0;
            boolean read = !write || (flags & O_RDWR) != 0;
            boolean create = (flags & O_CREAT) != 0;
            boolean exclusive = (flags & O_EXCL) != 0;
            boolean truncate = (flags & O_TRUNC) != 0;

            if (exclusive && Files.exists(nioPath)) {
                throw new NativeIOException("File exists", Errno.EEXIST);
            }
            if (create && nioPath.getParent() != null) {
                Files.createDirectories(nioPath.getParent());
            }

            RandomAccessFile raf;
            if (write) {
                if (!Files.exists(nioPath) && !create) {
                    throw new NativeIOException("No such file or directory", Errno.ENOENT);
                }
                raf = new RandomAccessFile(nioPath.toFile(), "rw");
                if (truncate) {
                    raf.setLength(0L);
                }
                if ((flags & O_APPEND) != 0) {
                    raf.seek(raf.length());
                }
            } else if (read) {
                raf = new RandomAccessFile(nioPath.toFile(), "r");
            } else {
                throw new IOException("Unsupported open flags: " + flags);
            }

            FileDescriptor fd = raf.getFD();
            OPEN_HANDLES.put(fd, raf);
            chmod(path, mode);
            return fd;
        }

        public static void chmod(String path, int mode) throws IOException {
            applyMode(new File(path).toPath(), mode);
        }

        static void posixFadviseIfPossible(String identifier,
                                           FileDescriptor fd, long offset, long len, int flags) {
            // Best-effort Java implementation has no portable equivalent.
        }

        public static void syncFileRangeIfPossible(FileDescriptor fd, long offset,
                                                   long nbytes, int flags) {
            // Best-effort Java implementation has no portable equivalent.
        }

        static void mlock(ByteBuffer buffer, long len) throws IOException {
            if (!buffer.isDirect()) {
                throw new IOException("Cannot mlock a non-direct ByteBuffer");
            }
            // No portable Java equivalent.
        }

        public static void munmap(MappedByteBuffer buffer) {
            if (CleanerUtil.UNMAP_SUPPORTED) {
                try {
                    CleanerUtil.getCleaner().freeBuffer(buffer);
                } catch (IOException e) {
                    LOG.info("Failed to unmap the buffer", e);
                }
            } else {
                LOG.trace(CleanerUtil.UNMAP_NOT_SUPPORTED_REASON);
            }
        }

        public static class Stat {
            private int ownerId;
            private int groupId;
            private String owner;
            private String group;
            private int mode;

            public static int S_IFMT = 0170000;
            public static int S_IFIFO = 0010000;
            public static int S_IFCHR = 0020000;
            public static int S_IFDIR = 0040000;
            public static int S_IFBLK = 0060000;
            public static int S_IFREG = 0100000;
            public static int S_IFLNK = 0120000;
            public static int S_IFSOCK = 0140000;
            public static int S_ISUID = 0004000;
            public static int S_ISGID = 0002000;
            public static int S_ISVTX = 0001000;
            public static int S_IRUSR = 0000400;
            public static int S_IWUSR = 0000200;
            public static int S_IXUSR = 0000100;

            Stat(int ownerId, int groupId, int mode) {
                this.ownerId = ownerId;
                this.groupId = groupId;
                this.mode = mode;
            }

            Stat(String owner, String group, int mode) {
                this.owner = Shell.WINDOWS ? stripDomain(owner) : owner;
                this.group = Shell.WINDOWS ? stripDomain(group) : group;
                this.mode = mode;
            }

            @Override
            public String toString() {
                return "Stat(owner='" + owner + "', group='" + group + "', mode=" + mode + ")";
            }

            public String getOwner() {
                return owner;
            }

            public String getGroup() {
                return group;
            }

            public int getMode() {
                return mode;
            }
        }

        public static Stat getFstat(FileDescriptor fd) {
            return new Stat(currentUser(), currentGroup(), Stat.S_IFREG | 0644);
        }

        public static Stat getStat(String path) throws IOException {
            if (path == null) {
                throw new IOException("Path is null");
            }
            try {
                return statFromPath(new File(path).toPath());
            } catch (IOException e) {
                throw new PathIOException(path, e);
            }
        }

        public final static int MMAP_PROT_READ = 0x1;
        public final static int MMAP_PROT_WRITE = 0x2;
        public final static int MMAP_PROT_EXEC = 0x4;

        public static long mmap(FileDescriptor fd, int prot,
                                boolean shared, long length) throws IOException {
            throw new IOException("mmap is not supported without native Hadoop libraries");
        }

        public static void munmap(long addr, long length) throws IOException {
            throw new IOException("munmap is not supported without native Hadoop libraries");
        }
    }

    public static class Windows {
        public static final long GENERIC_READ = 0x80000000L;
        public static final long GENERIC_WRITE = 0x40000000L;

        public static final long FILE_SHARE_READ = 0x00000001L;
        public static final long FILE_SHARE_WRITE = 0x00000002L;
        public static final long FILE_SHARE_DELETE = 0x00000004L;

        public static final long CREATE_NEW = 1;
        public static final long CREATE_ALWAYS = 2;
        public static final long OPEN_EXISTING = 3;
        public static final long OPEN_ALWAYS = 4;
        public static final long TRUNCATE_EXISTING = 5;

        public static final long FILE_BEGIN = 0;
        public static final long FILE_CURRENT = 1;
        public static final long FILE_END = 2;

        public static final long FILE_ATTRIBUTE_NORMAL = 0x00000080L;

        public static void createDirectoryWithMode(File path, int mode)
                throws IOException {
            Files.createDirectories(path.toPath());
            POSIX.chmod(path.getAbsolutePath(), mode);
        }

        public static FileDescriptor createFile(String path,
                                                long desiredAccess, long shareMode, long creationDisposition)
                throws IOException {
            File file = new File(path);
            boolean writable = (desiredAccess & GENERIC_WRITE) != 0;
            boolean readable = (desiredAccess & GENERIC_READ) != 0 || !writable;

            if (creationDisposition == CREATE_NEW && file.exists()) {
                throw new NativeIOException("The file exists", 80);
            }
            if ((creationDisposition == OPEN_EXISTING || creationDisposition == TRUNCATE_EXISTING)
                    && !file.exists()) {
                throw new NativeIOException("The system cannot find the file specified", 2);
            }
            if ((creationDisposition == CREATE_ALWAYS || creationDisposition == OPEN_ALWAYS
                    || creationDisposition == CREATE_NEW) && file.getParentFile() != null) {
                Files.createDirectories(file.getParentFile().toPath());
            }

            RandomAccessFile raf = new RandomAccessFile(file, writable ? "rw" : "r");
            if (creationDisposition == CREATE_ALWAYS || creationDisposition == TRUNCATE_EXISTING) {
                raf.setLength(0L);
            }
            if (!readable && writable) {
                raf.seek(raf.length());
            }
            FileDescriptor fd = raf.getFD();
            OPEN_HANDLES.put(fd, raf);
            return fd;
        }

        public static FileOutputStream createFileOutputStreamWithMode(File path,
                                                                      boolean append, int mode)
                throws IOException {
            if (path.getParentFile() != null) {
                Files.createDirectories(path.getParentFile().toPath());
            }
            FileOutputStream outputStream = new FileOutputStream(path, append);
            POSIX.chmod(path.getAbsolutePath(), mode);
            return outputStream;
        }

        public static long setFilePointer(FileDescriptor fd,
                                          long distanceToMove, long moveMethod) throws IOException {
            Closeable closeable = OPEN_HANDLES.get(fd);
            if (!(closeable instanceof RandomAccessFile)) {
                throw new NativeIOException("The handle is invalid.", Errno.EBADF);
            }
            RandomAccessFile raf = (RandomAccessFile) closeable;
            long target;
            if (moveMethod == FILE_BEGIN) {
                target = distanceToMove;
            } else if (moveMethod == FILE_CURRENT) {
                target = raf.getFilePointer() + distanceToMove;
            } else if (moveMethod == FILE_END) {
                target = raf.length() + distanceToMove;
            } else {
                throw new IOException("Unsupported move method: " + moveMethod);
            }
            raf.seek(target);
            return target;
        }

        private static String getOwner(FileDescriptor fd) {
            return currentUser();
        }

        public enum AccessRight {
            ACCESS_READ(0x0001),
            ACCESS_WRITE(0x0002),
            ACCESS_EXECUTE(0x0020);

            private final int accessRight;

            AccessRight(int access) {
                accessRight = access;
            }

            public int accessRight() {
                return accessRight;
            }
        }

        public static boolean access(String path, AccessRight desiredAccess) {
            Path nioPath = new File(path).toPath();
            if (desiredAccess == AccessRight.ACCESS_READ) {
                return Files.isReadable(nioPath);
            }
            if (desiredAccess == AccessRight.ACCESS_WRITE) {
                return Files.isWritable(nioPath);
            }
            return Files.isExecutable(nioPath);
        }

        public static void extendWorkingSetSize(long delta) {
            // No portable Java equivalent.
        }
    }

    public static boolean isAvailable() {
        return false;
    }

    static long getMemlockLimit() {
        return 0L;
    }

    static long getOperatingSystemPageSize() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Unsafe unsafe = (Unsafe) f.get(null);
            return unsafe.pageSize();
        } catch (Throwable e) {
            LOG.warn("Unable to get operating system page size. Guessing 4096.", e);
            return 4096;
        }
    }

    public static String getOwner(FileDescriptor fd) throws IOException {
        ensureInitialized();
        if (Shell.WINDOWS) {
            return stripDomain(Windows.getOwner(fd));
        }
        return currentUser();
    }

    public static FileDescriptor getShareDeleteFileDescriptor(File f, long seekOffset)
            throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        if (seekOffset > 0) {
            raf.seek(seekOffset);
        }
        FileDescriptor fd = raf.getFD();
        OPEN_HANDLES.put(fd, raf);
        return fd;
    }

    public static FileOutputStream getCreateForWriteFileOutputStream(File f, int permissions)
            throws IOException {
        try {
            if (f.getParentFile() != null) {
                Files.createDirectories(f.getParentFile().toPath());
            }
            if (!f.createNewFile()) {
                throw new FileAlreadyExistsException(f.getAbsolutePath());
            }
            FileOutputStream outputStream = new FileOutputStream(f);
            POSIX.chmod(f.getCanonicalPath(), permissions);
            return outputStream;
        } catch (FileAlreadyExistsException e) {
            throw new AlreadyExistsException(e);
        }
    }

    private synchronized static void ensureInitialized() {
        if (!initialized) {
            cacheTimeout = new Configuration().getLong(
                    CommonConfigurationKeys.HADOOP_SECURITY_UID_NAME_CACHE_TIMEOUT_KEY,
                    CommonConfigurationKeys.HADOOP_SECURITY_UID_NAME_CACHE_TIMEOUT_DEFAULT) * 1000;
            LOG.info("Initialized cache for UID to User mapping with a cache timeout of {} seconds.",
                    cacheTimeout / 1000);
            initialized = true;
        }
    }

    public static void renameTo(File src, File dst) throws IOException {
        Files.move(src.toPath(), dst.toPath());
    }

    @Deprecated
    public static void link(File src, File dst) throws IOException {
        try {
            HardLink.createHardLink(src, dst);
        } catch (UnsupportedOperationException e) {
            Files.createLink(dst.toPath(), src.toPath());
        }
    }

    public static void copyFileUnbuffered(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileChannel input = fis.getChannel();
             FileOutputStream fos = new FileOutputStream(dst);
             FileChannel output = fos.getChannel()) {
            long remaining = input.size();
            long position = 0;
            while (remaining > 0) {
                long transferred = input.transferTo(position, remaining, output);
                if (transferred <= 0) {
                    break;
                }
                remaining -= transferred;
                position += transferred;
            }
        }
    }

    private static POSIX.Stat statFromPath(Path path) throws IOException {
        Path realPath = path;
        int mode = 0;
        if (Files.isSymbolicLink(realPath)) {
            mode |= POSIX.Stat.S_IFLNK;
        } else if (Files.isDirectory(realPath, LinkOption.NOFOLLOW_LINKS)) {
            mode |= POSIX.Stat.S_IFDIR;
        } else {
            mode |= POSIX.Stat.S_IFREG;
        }

        PosixFileAttributeView posixView = Files.getFileAttributeView(
                realPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        String owner = currentUser();
        String group = currentGroup();
        if (posixView != null) {
            PosixFileAttributes attrs = posixView.readAttributes();
            owner = attrs.owner().getName();
            GroupPrincipal groupPrincipal = attrs.group();
            if (groupPrincipal != null) {
                group = groupPrincipal.getName();
            }
            mode |= modeFromPermissions(attrs.permissions());
        } else {
            owner = Files.getOwner(realPath, LinkOption.NOFOLLOW_LINKS).getName();
            group = owner;
            if (Files.isReadable(realPath)) {
                mode |= 0444;
            }
            if (Files.isWritable(realPath)) {
                mode |= 0222;
            }
            if (Files.isExecutable(realPath)) {
                mode |= 0111;
            }
        }
        return new POSIX.Stat(owner, group, mode);
    }

    private static int modeFromPermissions(Set<PosixFilePermission> permissions) {
        int mode = 0;
        if (permissions.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
        if (permissions.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
        if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
        if (permissions.contains(PosixFilePermission.GROUP_READ)) mode |= 0040;
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)) mode |= 0020;
        if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) mode |= 0010;
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) mode |= 0004;
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) mode |= 0002;
        if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) mode |= 0001;
        return mode;
    }

    private static void applyMode(Path path, int mode) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NativeIOException("No such file or directory", Errno.ENOENT);
        }

        PosixFileAttributeView posixView = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView != null) {
            posixView.setPermissions(toPosixPermissions(mode));
            return;
        }

        File file = path.toFile();
        boolean anyRead = (mode & 0444) != 0;
        boolean anyWrite = (mode & 0222) != 0;
        boolean anyExecute = (mode & 0111) != 0;
        boolean ownerOnlyRead = (mode & 0044) == 0;
        boolean ownerOnlyWrite = (mode & 0022) == 0;
        boolean ownerOnlyExecute = (mode & 0011) == 0;

        if (!file.setReadable(anyRead, ownerOnlyRead)
                || !file.setWritable(anyWrite, ownerOnlyWrite)
                || !file.setExecutable(anyExecute, ownerOnlyExecute)) {
            throw new IOException("Failed to apply permissions " + Integer.toOctalString(mode)
                    + " to " + path);
        }
    }

    private static Set<PosixFilePermission> toPosixPermissions(int mode) {
        EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        return Collections.unmodifiableSet(permissions);
    }

    private static String currentUser() {
        return stripDomain(System.getProperty("user.name", "unknown"));
    }

    private static String currentGroup() {
        return currentUser();
    }

    private static String stripDomain(String name) {
        int i = name.indexOf('\\');
        if (i != -1) {
            return name.substring(i + 1);
        }
        return name;
    }
}
