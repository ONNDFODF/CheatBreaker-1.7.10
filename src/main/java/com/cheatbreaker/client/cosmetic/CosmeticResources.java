package com.cheatbreaker.client.cosmetic;

import com.cheatbreaker.client.util.Logging;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 枚举客户端内置装扮资源的工具类。
 *
 * <p>装扮贴图（披风 / 翅膀 / 帽子模型）和表情贴图随客户端以普通 jar 的形式放在 classpath 上，
 * {@code DefaultResourcePack#getResourceStream} 同样是通过 ClassLoader 读取
 * {@code /assets/minecraft/...}，所以这里沿用相同的语义：扫描 {@code java.class.path} 里的
 * jar 与目录，收集 {@code assets/minecraft/client/cosmetics/} 下的全部文件。
 *
 * <p>结果按路径排序并缓存，只在首次访问时访问一次磁盘，之后 {@link #list(String)} 就是纯内存过滤。
 */
public final class CosmeticResources {
    /** 资源根前缀：ResourceLocation 的路径会拼在它后面。 */
    private static final String ASSETS_ROOT = "assets/minecraft/";
    /** 本类关心的资源子树。 */
    private static final String COSMETICS_ROOT = "client/cosmetics";

    private static List<String> cachedEntries;

    private CosmeticResources() {
    }

    /**
     * 列出某个装扮子目录下的所有文件，返回形如 {@code client/cosmetics/capes/bee.png} 的路径。
     *
     * @param directory 例如 {@code client/cosmetics/capes}，末尾斜杠可有可无
     */
    public static List<String> list(String directory) {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        List<String> result = new ArrayList<String>();

        for (String entry : entries()) {
            if (entry.startsWith(prefix)) {
                result.add(entry);
            }
        }

        return result;
    }

    /** 判断 {@code client/cosmetics/...} 形式的路径在 classpath 上是否存在。 */
    public static boolean exists(String path) {
        try (InputStream stream = open(path)) {
            return stream != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** 打开 {@code client/cosmetics/...} 形式的资源，找不到时返回 {@code null}。 */
    public static InputStream open(String path) {
        return CosmeticResources.class.getResourceAsStream("/" + ASSETS_ROOT + path);
    }

    /** 全部装扮资源条目（相对 {@code assets/minecraft/}），按字母序去重。 */
    private static List<String> entries() {
        if (cachedEntries != null) {
            return cachedEntries;
        }

        String prefix = ASSETS_ROOT + COSMETICS_ROOT + "/";
        TreeSet<String> found = new TreeSet<String>();

        for (File root : classPathRoots()) {
            try {
                if (root.isDirectory()) {
                    collectDirectory(root, prefix, found);
                } else if (root.isFile() && root.getName().toLowerCase().endsWith(".jar")) {
                    collectJar(root, prefix, found);
                }
            } catch (IOException e) {
                Logging.warnTagged("Cosmetics", "Could not scan %s for cosmetics: %s", root, e);
            }
        }

        Logging.debugTagged("Cosmetics", "Found %d cosmetic resource files on the classpath", found.size());
        cachedEntries = Collections.unmodifiableList(new ArrayList<String>(found));
        return cachedEntries;
    }

    /** 从目录里的资源包读取条目。 */
    private static void collectDirectory(File root, String prefix, TreeSet<String> found) {
        collectDirectoryEntries(new File(root, prefix), COSMETICS_ROOT, found);
    }

    private static void collectDirectoryEntries(File directory, String relative, TreeSet<String> found) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            String path = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (child.isDirectory()) {
                collectDirectoryEntries(child, path, found);
            } else {
                found.add(path);
            }
        }
    }

    /** 从 jar 资源包里读取条目。 */
    private static void collectJar(File jar, String prefix, TreeSet<String> found) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (entry.isDirectory() || !name.startsWith(prefix)) {
                    continue;
                }

                found.add(name.substring(ASSETS_ROOT.length()));
            }
        }
    }

    /** classpath 上的 jar 与目录，外加本类所在的 code source（应对自定义 ClassLoader）。 */
    private static List<File> classPathRoots() {
        List<File> roots = new ArrayList<File>();
        String classPath = System.getProperty("java.class.path", "");

        for (String element : classPath.split(File.pathSeparator)) {
            if (element.isEmpty()) {
                continue;
            }

            File file = new File(element);
            if (file.exists()) {
                roots.add(file);
            }
        }

        try {
            URL location = CosmeticResources.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equals(location.getProtocol())) {
                File file = new File(location.toURI());
                if (file.exists()) {
                    roots.add(file);
                }
            }
        } catch (Exception e) {
            Logging.debugTagged("Cosmetics", "Could not resolve own code source: %s", e);
        }

        return roots;
    }
}
