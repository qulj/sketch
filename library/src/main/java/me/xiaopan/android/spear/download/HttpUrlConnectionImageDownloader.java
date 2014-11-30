/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.android.spear.download;

import android.os.Build;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

import me.xiaopan.android.spear.Spear;
import me.xiaopan.android.spear.request.DownloadRequest;

/**
 * 使用HttpURLConnection来访问网络的下载器
 */
public class HttpUrlConnectionImageDownloader implements ImageDownloader {
    private static final int BUFFER_SIZE = 8*1024;
    private static final int DEFAULT_READ_TIMEOUT = 10*1000;   // 默认读取超时时间
    private static final int DEFAULT_CONNECT_TIMEOUT = 10*1000;    // 默认连接超时时间
    private static final int DEFAULT_MAX_RETRY_COUNT = 1;    // 默认最大重试次数
    private static final int DEFAULT_PROGRESS_CALLBACK_NUMBER = 10;    // 默认进度回调次数
	private static final String NAME = HttpUrlConnectionImageDownloader.class.getSimpleName();

    private Set<String> downloadingFiles;
	private Map<String, ReentrantLock> urlLocks;
    private int maxRetryCount = DEFAULT_MAX_RETRY_COUNT;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;
    private int progressCallbackNumber = DEFAULT_PROGRESS_CALLBACK_NUMBER;

	public HttpUrlConnectionImageDownloader() {
		this.urlLocks = Collections.synchronizedMap(new WeakHashMap<String, ReentrantLock>());
		this.downloadingFiles = Collections.synchronizedSet(new HashSet<String>());
	}

    @Override
    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @Override
    public void setProgressCallbackNumber(int progressCallbackNumber) {
        this.progressCallbackNumber = progressCallbackNumber;
    }

    /**
     * 获取一个URL锁，通过此锁可以防止重复下载
     * @param url 下载地址
     * @return URL锁
     */
	public synchronized ReentrantLock getUrlLock(String url){
		ReentrantLock urlLock = urlLocks.get(url);
		if(urlLock == null){
			urlLock = new ReentrantLock();
			urlLocks.put(url, urlLock);
		}
		return urlLock;
	}

	@Override
	public synchronized boolean isDownloadingByCacheFilePath(String cacheFilePath) {
		return downloadingFiles.contains(cacheFilePath);
	}

    private HttpURLConnection openUrlConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        // HTTP connection reuse which was buggy pre-froyo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            connection.setRequestProperty("http.keepAlive", "false");
        }
        return connection;
    }

    @Override
	public DownloadResult download(DownloadRequest request) {
        // 根据下载地址加锁，防止重复下载
        ReentrantLock urlLock = getUrlLock(request.getUri());
        urlLock.lock();

        DownloadResult result = null;
        int number = 0;
        while(true){
            if (request.isCanceled()) {
                if (Spear.isDebugMode()) {
                    Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - 拿到锁之后" + "；" + request.getName());
                }
                break;
            }

            // 如果缓存文件已经存在了就直接返回缓存文件
            File cacheFile = request.getCacheFile();
            if (cacheFile != null && cacheFile.exists()) {
                result = DownloadResult.createByFile(cacheFile, false);
                break;
            }

            try {
                result = realDownload(request);
                break;
            } catch (Throwable e) {
                e.printStackTrace();
                boolean retry = (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) && number < maxRetryCount;
                if(retry){
                    number++;
                    Log.w(Spear.LOG_TAG, NAME + "；" + "下载异常 - 再次尝试" + "；" + request.getName());
                }else{
                    Log.e(Spear.LOG_TAG, NAME + "；" + "下载异常 - 不再尝试" + "；" + request.getName());
                }
                if(!retry){
                    break;
                }
            }
        }

        // 释放锁
        urlLock.unlock();
        return result;
    }

    private DownloadResult realDownload(DownloadRequest request) throws IOException {
        // 打开连接
        HttpURLConnection connection;
        try {
            connection = openUrlConnection(request.getUri());
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "创建连接失败："+e.getMessage() + "；" + request.getName());
            throw e;
        }
        try {
            connection.connect();
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "打开连接失败："+e.getMessage() + "；" + request.getName());
            throw e;
        }
        if (request.isCanceled()) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - 打开连接之后" + "；" + request.getName());
            releaseConnection(connection, request);
            return null;
        }

        // 检查状态码
        int responseCode;
        try {
            responseCode = connection.getResponseCode();
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "获取状态码时发生异常" + "；" + request.getName());
            releaseConnection(connection, request);
            return null;
        }
        String responseMessage;
        try {
            responseMessage = connection.getResponseMessage();
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "获取状态消息时发生异常" + "；" + request.getName());
            releaseConnection(connection, request);
            return null;
        }
        if (responseCode < 200 || responseCode > 299) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "状态码异常："+responseCode+" "+responseMessage + "；" + request.getName());
            releaseConnection(connection, request);
            return null;
        }

        // 检查内容长度
        int contentLength = connection.getHeaderFieldInt("Content-Length", -1);
        if (contentLength <= 0) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "内容长度异常："+contentLength + "；" + request.getName());
            releaseConnection(connection, request);
            return null;
        }

        return readData(request, connection, contentLength);
    }

    private DownloadResult readData(DownloadRequest request, HttpURLConnection connection, int contentLength) throws IOException {
        // 根据需求创建缓存文件
        File cacheFile = request.getCacheFile();
        boolean saveToCacheFile = cacheFile != null && request.getSpear().getConfiguration().getDiskCache().applyForSpace(contentLength) && createCacheFile(cacheFile);

        // 由于判定缓存文件是否可用的条件是本地是否有对应的缓存文件
        // 但是正在下载中的缓存文件是不可以用的，因此要在次锁定文件
        // 并在判定缓存文件是否可用的时候再来这里查询下缓存文件是否被锁定
        if (saveToCacheFile) {
            downloadingFiles.add(cacheFile.getPath());
        }

        // 获取输入流
        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "获取输入流时发生异常："+e.getMessage() + "；" + request.getName());
            if (saveToCacheFile) downloadingFiles.remove(cacheFile.getPath());
            if (saveToCacheFile && cacheFile.exists() && !cacheFile.delete() && Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败：" + cacheFile.getPath() + "；" + request.getName());
            throw e;
        }
        if (request.isCanceled()) {
            close(inputStream);
            if (saveToCacheFile) downloadingFiles.remove(cacheFile.getPath());
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - 获取输入流之后" + "；" + request.getName());
            if (saveToCacheFile && cacheFile.exists() && !cacheFile.delete() && Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败："+cacheFile.getPath() + "；" + request.getName());
            return null;
        }

        // 当不需要将数据缓存到本地的时候就使用ByteArrayOutputStream来存储数据
        OutputStream outputStream;
        if(saveToCacheFile){
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(cacheFile, false), BUFFER_SIZE);
            } catch (FileNotFoundException e) {
                close(inputStream);
                downloadingFiles.remove(cacheFile.getPath());
                if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "创建输出流时发生异常："+e.getMessage() + "；" + request.getName());
                if (cacheFile.exists() && !cacheFile.delete() && Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败："+cacheFile.getPath() + "；" + request.getName());
                throw e;
            }
        }else{
            outputStream = new ByteArrayOutputStream();
        }

        // 读取数据
        int completedLength = 0;
        boolean exception = false;
        try {
            completedLength = readData(inputStream, outputStream, request, contentLength, progressCallbackNumber);
        } catch (IOException e) {
            exception = true;
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "读取数据时发生异常："+e.getMessage() + "；" + request.getName());
            throw e;
        }finally {
            close(outputStream);
            close(inputStream);
            if (saveToCacheFile) downloadingFiles.remove(cacheFile.getPath());
            if (exception && saveToCacheFile && cacheFile.exists() && !cacheFile.delete() && Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败："+cacheFile.getPath() + "；" + request.getName());
        }
        if (request.isCanceled()) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "已取消下载 - 读取完数据之后" + "；" + request.getName());
            if (saveToCacheFile && cacheFile.exists() && !cacheFile.delete() && Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "删除缓存文件失败："+cacheFile.getPath() + "；" + request.getName());
            return null;
        }

        if (Spear.isDebugMode())  Log.i(Spear.LOG_TAG, NAME + "：" + "下载成功" + "；" + "文件长度：" + completedLength + "/" + contentLength + "；" + request.getName());

        // 转换结果
        if(outputStream instanceof ByteArrayOutputStream){
            return DownloadResult.createByByteArray(((ByteArrayOutputStream) outputStream).toByteArray(), true);
        }else{
            return DownloadResult.createByFile(cacheFile, true);
        }
    }

    public static void close(Closeable closeable){
        if(closeable == null){
            return;
        }

        if(closeable instanceof OutputStream){
            try {
                ((OutputStream) closeable).flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            closeable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void releaseConnection(HttpURLConnection connection, DownloadRequest request){
        if(connection == null){
            return;
        }

        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException e) {
            if (Spear.isDebugMode()) Log.w(Spear.LOG_TAG, NAME + "：" + "释放连接时获取输入流失败："+e.getMessage() + "；" + request.getName());
            return;
        }
        close(inputStream);
    }

    public static int readData(InputStream inputStream, OutputStream outputStream, DownloadRequest downloadRequest, int contentLength, int progressCallbackAccuracy) throws IOException {
        int readNumber;
        int completedLength = 0;
        int averageLength = contentLength/progressCallbackAccuracy;
        int callbackNumber = 0;
        byte[] cacheBytes = new byte[4*1024];
        while(!downloadRequest.isCanceled() && (readNumber = inputStream.read(cacheBytes)) != -1){
            outputStream.write(cacheBytes, 0, readNumber);
            completedLength += readNumber;
            if(downloadRequest.getDownloadProgressListener() != null && (completedLength >= (callbackNumber+1)*averageLength || completedLength == contentLength)){
                callbackNumber++;
                downloadRequest.getDownloadProgressListener().onUpdateProgress(contentLength, completedLength);
            }
        }
        outputStream.flush();
        return completedLength;
    }

    public static boolean createCacheFile(File file){
        if(file.exists()){
           return true;
        }

        File parentDir = file.getParentFile();
        if(!parentDir.exists() && !parentDir.mkdirs()){
            return false;
        }
        try {
            if(!file.createNewFile()){
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
