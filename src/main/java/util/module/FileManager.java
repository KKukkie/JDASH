package util.module;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class FileManager {

    private static final Logger logger = LoggerFactory.getLogger(FileManager.class);

    public static boolean isExist(String fileName) {
        if (fileName == null) { return false; }

        File file = new File(fileName);
        return file.exists();
    }

    public static boolean mkdirs(String fileName) {
        if (fileName == null) { return false; }

        File file = new File(fileName);
        return file.mkdirs();
    }

    public static boolean writeBytes(File file, byte[] data, boolean isAppend) {
        if (file == null || data == null || data.length == 0) { return false; }

        BufferedOutputStream bufferedOutputStream = null;
        try {
            bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(file, isAppend));
            bufferedOutputStream.write(data);
            return true;
        } catch (Exception e) {
            logger.warn("[FileManager] Fail to write the file. (fileName={})", file.getAbsolutePath(), e);
            return false;
        } finally {
            try {
                if (bufferedOutputStream != null) {
                    bufferedOutputStream.close();
                }
            } catch (Exception e) {
                logger.warn("[FileManager] Fail to close the buffer stream. (fileName={})", file.getAbsolutePath());
            }
        }
    }


    public static boolean writeBytes(String fileName, byte[] data, boolean isAppend) {
        if (fileName == null) { return false; }

        BufferedOutputStream bufferedOutputStream = null;
        try {
            bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(fileName, isAppend));
            bufferedOutputStream.write(data);
            return true;
        } catch (Exception e) {
            logger.warn("[FileManager] Fail to write the file. (fileName={})", fileName, e);
            return false;
        } finally {
            try {
                if (bufferedOutputStream != null) {
                    bufferedOutputStream.close();
                }
            } catch (Exception e) {
                logger.warn("[FileManager] Fail to close the buffer stream. (fileName={})", fileName, e);
            }
        }
    }

    public static boolean writeString(String fileName, String data, boolean isAppend) {
        if (fileName == null) { return false; }

        BufferedWriter bufferedWriter = null;
        try {
            bufferedWriter = new BufferedWriter(new FileWriter(fileName, isAppend));
            bufferedWriter.write(data);
            return true;
        } catch (Exception e) {
            logger.warn("[FileManager] Fail to write the file. (fileName={})", fileName, e);
            return false;
        } finally {
            try {
                if (bufferedWriter != null) {
                    bufferedWriter.close();
                }
            } catch (Exception e) {
                logger.warn("[FileManager] Fail to close the buffer stream. (fileName={})", fileName, e);
            }
        }
    }

    public static byte[] readAllBytes(String fileName) {
        if (fileName == null) { return null; }

        BufferedInputStream bufferedInputStream = null;
        try {
            bufferedInputStream = new BufferedInputStream(new FileInputStream(fileName));
            return bufferedInputStream.readAllBytes();
        } catch (Exception e) {
            logger.warn("[FileManager] Fail to read the file. (fileName={})", fileName);
            return null;
        } finally {
            try {
                if (bufferedInputStream != null) {
                    bufferedInputStream.close();
                }
            } catch (IOException e) {
                logger.warn("[FileManager] Fail to close the buffer stream. (fileName={})", fileName);
            }
        }
    }

    public static List<String> readAllLines(String fileName) {
        if (fileName == null) { return null; }

        BufferedReader bufferedReader = null;
        List<String> lines = new ArrayList<>();
        try {
            bufferedReader = new BufferedReader(new FileReader(fileName));
            String line;
            while( (line = bufferedReader.readLine()) != null ) {
                lines.add(line);
            }
            return lines;
        } catch (Exception e) {
            logger.warn("[FileManager] Fail to read the file. (fileName={})", fileName);
            return lines;
        } finally {
            try {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            } catch (IOException e) {
                logger.warn("[FileManager] Fail to close the buffer reader. (fileName={})", fileName, e);
            }
        }
    }

    // [/home/uangel/udash/media] + [animal/tigers/tigers.mp4] > [/home/uangel/udash/media/animal/tigers/tigers.mp4]
    public static String concatFilePath(String from, String to) {
        if (from == null) { return null; }
        if (to == null) { return from; }

        String resultPath = from.trim();
        if (!to.startsWith(File.separator)) {
            if (!resultPath.endsWith(File.separator)) {
                resultPath += File.separator;
            }
        } else {
            if (resultPath.endsWith(File.separator)) {
                resultPath = resultPath.substring(0, resultPath.lastIndexOf("/"));
            }
        }

        resultPath += to.trim();
        return resultPath;
    }

    // [/home/uangel/udash/media/animal/tigers/tigers.mp4] > [/home/uangel/udash/media/animal/tigers]
    public static String getParentPathFromUri(String uri) {
        if (uri == null) { return null; }
        if (!uri.contains("/")) { return uri; }
        return uri.substring(0, uri.lastIndexOf("/")).trim();
    }

    // [/home/uangel/udash/media/animal/tigers/tigers.mp4] > [tigers.mp4]
    public static String getFileNameWithExtensionFromUri(String uri) {
        if (uri == null) { return null; }
        if (!uri.contains("/")) { return uri; }

        int lastSlashIndex = uri.lastIndexOf("/");
        if (lastSlashIndex == (uri.length() - 1)) { return null; }
        return uri.substring(uri.lastIndexOf("/") + 1).trim();
    }

    // [/home/uangel/udash/media/animal/tigers/tigers.mp4] > [/home/uangel/udash/media/animal/tigers/tigers]
    public static String getFilePathWithoutExtensionFromUri(String uri) {
        if (uri == null) { return null; }
        if (!uri.contains(".")) { return uri; }
        return uri.substring(0, uri.lastIndexOf(".")).trim();
    }

    // [/home/uangel/udash/media/animal/tigers/tigers.mp4] > [tigers]
    public static String getFileNameFromUri(String uri) {
        uri = getFileNameWithExtensionFromUri(uri);
        if (uri == null) { return null; }
        if (!uri.contains(".")) { return uri; }

        uri = uri.substring(0, uri.lastIndexOf(".")).trim();
        return uri;
    }

    public static void deleteFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            logger.warn("[FileManager] Fail to delete the file. File is not exist. (path={})", path);
            return;
        }

        try {
            if (file.isDirectory()) {
                FileUtils.deleteDirectory(file);
            } else {
                FileUtils.fileDelete(path);
            }
            logger.debug("[FileManager] Success to delete the file. (path={})", path);
        } catch (Exception e) {
            logger.warn("[FileManager] Fail to delete the file. (path={})", path, e);
        }
    }

    public static void deleteOldFilesBySecond(String rootPath, String[] exceptFileNameList, String[] exceptFileExtensionList, long limitTime) throws IOException {
        File rootPathFile = new File(rootPath);
        File[] list = rootPathFile.listFiles();
        if (list == null || list.length == 0) { return; }

        for (File curFile : list) {
            if (curFile == null || !curFile.exists() || curFile.isDirectory()) { continue; }

            ///////////////////////////////
            boolean isExcepted = false;
            // 1) 제외할 파일 이름 확인
            for (String exceptFileName : exceptFileNameList) {
                if (curFile.getAbsolutePath().contains(exceptFileName)) {
                    isExcepted = true;
                    break;
                }
            }
            // 2) 제외할 파일 확장자 확인
            for (String exceptFileExtension : exceptFileExtensionList) {
                String curFileExtension = FileUtils.getExtension(curFile.getAbsolutePath());
                if (curFileExtension.isEmpty()) { continue; }
                if (curFileExtension.equals(exceptFileExtension)) {
                    isExcepted = true;
                    break;
                }
            }
            if (isExcepted) { continue; }
            ///////////////////////////////

            ///////////////////////////////
            // 3) 파일 마지막 수정 시간 확인
            long lastModifiedTime = getLastModificationSecondTime(curFile);
            if (lastModifiedTime >= limitTime) { // 제한 시간 [이상] 경과
                if (curFile.delete()) {
                    logger.trace("[FileManager] Old file({}) is deleted. (lastModifiedTime=[{}]sec, limitTime=[{}]sec)",
                            curFile.getAbsolutePath(),
                            lastModifiedTime, limitTime
                    );
                }
            }
            ///////////////////////////////
        }
    }

    private static long getLastModificationSecondTime(File file) throws IOException {
        Path attribPath = file.toPath();
        BasicFileAttributes basicAttr = Files.readAttributes(attribPath, BasicFileAttributes.class);
        return (System.currentTimeMillis() - basicAttr.lastModifiedTime().to(TimeUnit.MILLISECONDS)) / 1000;
    }

}
