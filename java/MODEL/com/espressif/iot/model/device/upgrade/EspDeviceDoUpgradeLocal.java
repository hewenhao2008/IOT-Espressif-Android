package com.espressif.iot.model.device.upgrade;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;

import com.espressif.iot.base.api.EspBaseApiUtil;
import com.espressif.iot.base.application.EspApplication;
import com.espressif.iot.base.net.rest.mesh.EspPureSocketNetUtil;
import com.espressif.iot.base.net.rest.mesh.EspSocketClient;
import com.espressif.iot.db.IOTDownloadIdValueDBManager;
import com.espressif.iot.device.upgrade.IEspDeviceDoUpgradeLocal;
import com.espressif.iot.device.upgrade.IEspDeviceUpgradeInfo;
import com.espressif.iot.device.upgrade.IEspDeviceUpgradeParser;
import com.espressif.iot.type.net.IOTAddress;
import com.espressif.iot.util.EspStrings;

public class EspDeviceDoUpgradeLocal implements IEspDeviceDoUpgradeLocal
{
    
    private final static Logger log = Logger.getLogger(EspDeviceDoUpgradeLocal.class);
    
    private String __getUpgradeGetUserUrl(InetAddress inetAddress)
    {
        return "http:/" + inetAddress.toString() + "/upgrade?command=getuser";
    }
    
    private String __getDownloadUrl(String version, String fileName)
    {
        return URL_DOWNLOAD_BIN + "?action=download_rom&version=" + version + "&filename=" + fileName;
    }
    
    private String __getUpgradePushUrl(InetAddress inetAddress, boolean isUser1)
    {
        if (isUser1)
        {
            return "http:/" + inetAddress.toString() + "/device/bin/upgrade/?bin=user1.bin";
        }
        else
        {
            return "http:/" + inetAddress.toString() + "/device/bin/upgrade/?bin=user2.bin";
        }
    }
    
    private String __getResetUrl(InetAddress inetAddress)
    {
        return "http:/" + inetAddress.toString() + "/upgrade?command=reset";
    }
    
    private String __getStartUrl(InetAddress inetAddress)
    {
        return "http:/" + inetAddress.toString() + "/upgrade?command=start";
    }
    
    private void __setHttpClientParams(DefaultHttpClient httpClient)
    {
        BasicHttpParams params = new BasicHttpParams();
        params.setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, CONNECTION_TIMEOUT);
        params.setParameter(CoreConnectionPNames.SO_TIMEOUT, SO_TIMEOUT);
        httpClient.setParams(params);
    }
    
    /**
     * download file from server and save it in local
     * 
     * @param httpGet the httpGet
     * @param folderPath
     * @param saveFileName
     * @return
     */
    private boolean __download(HttpGet httpGet, String folderPath, String saveFileName)
    {
        File folderFile = new File(folderPath);
        if (!folderFile.exists())
        {
            folderFile.mkdirs();
        }
        
        DefaultHttpClient httpClient = new DefaultHttpClient();
        __setHttpClientParams(httpClient);
        try
        {
            HttpResponse httpResponse = httpClient.execute(httpGet);
            
            if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK)
            {
                InputStream is = httpResponse.getEntity().getContent();
                // Start download file
                FileOutputStream fos = new FileOutputStream(folderPath + "/" + saveFileName);
                byte[] buffer = new byte[8192];
                int count = 0;
                while ((count = is.read(buffer)) != -1)
                {
                    fos.write(buffer, 0, count);
                }
                fos.close();
                is.close();
                log.debug(Thread.currentThread().toString() + "##__download(httpGet=[" + httpGet + "],folderPath=["
                    + folderPath + "],saveFileName=[" + saveFileName + "]): " + true);
                return true;
            }
        }
        catch (ClientProtocolException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        finally
        {
            httpGet.abort();
            httpClient.getConnectionManager().shutdown();
        }
        log.warn(Thread.currentThread().toString() + "##__download(httpGet=[" + httpGet + "],folderPath=[" + folderPath
            + "],saveFileName=[" + saveFileName + "]): " + false);
        return false;
    }
    
    private void __saveDownloadIdValue(String version)
    {
        log.debug(Thread.currentThread().toString() + "##__saveDownloadIdValue(version=[" + version + "])");
        IEspDeviceUpgradeParser parser = EspDeviceUpgradeParser.getInstance();
        IEspDeviceUpgradeInfo romVersionInfo = parser.parseUpgradeInfo(version);
        long downloadIdValue = romVersionInfo.getIdValue();
        IOTDownloadIdValueDBManager downloadIdValueDBManager = IOTDownloadIdValueDBManager.getInstance();
        downloadIdValueDBManager.insertDownloadIdValueIfNotExist(downloadIdValue);
    }
    
    private void __deleteDownloadIdValue(String version)
    {
        log.debug(Thread.currentThread().toString() + "##__deleteDownloadIdValue(version=[" + version + "])");
        IEspDeviceUpgradeParser parser = EspDeviceUpgradeParser.getInstance();
        IEspDeviceUpgradeInfo romVersionInfo = parser.parseUpgradeInfo(version);
        long downloadIdValue = romVersionInfo.getIdValue();
        IOTDownloadIdValueDBManager downloadIdValueDBManager = IOTDownloadIdValueDBManager.getInstance();
        downloadIdValueDBManager.deleteDownloadIdValueIfExist(downloadIdValue);
    }
    
    /**
     * whether the device is running user1.bin
     * 
     * @param inetAddress the ip address
     * @return true if user1.bin is running,false if user2.bin is running, null if device don't response
     */
    private Boolean __isUser1Running(InetAddress inetAddress)
    {
        JSONObject jsonObjectResult = EspBaseApiUtil.Get(__getUpgradeGetUserUrl(inetAddress));
        if (jsonObjectResult == null)
        {
            log.warn(Thread.currentThread().toString() + "##__isUser1Running(inetAddress=[" + inetAddress + "]):1 "
                + null);
            return null;
        }
        // get getUser result
        String userResult = null;
        try
        {
            userResult = jsonObjectResult.getString(USER_BIN);
        }
        catch (JSONException e)
        {
            e.printStackTrace();
        }
        if (userResult.equals(USER1_BIN))
        {
            log.debug(Thread.currentThread().toString() + "##__isUser1Running(inetAddress=[" + inetAddress + "]): "
                + true);
            return true;
        }
        else if (userResult.equals(USER2_BIN))
        {
            log.debug(Thread.currentThread().toString() + "##__isUser1Running(inetAddress=[" + inetAddress + "]): "
                + false);
            return false;
        }
        log.warn(Thread.currentThread().toString() + "##__isUser1Running(inetAddress=[" + inetAddress + "]):2 " + null);
        return null;
    }
    
    private byte[] __loadBinFromLocal(boolean isUser1, String latestVersion)
    {
        IEspDeviceUpgradeParser parser = EspDeviceUpgradeParser.getInstance();
        IEspDeviceUpgradeInfo latestRomVersionInfo = parser.parseUpgradeInfo(latestVersion);
        String subPath = Long.toString(latestRomVersionInfo.getIdValue());
        String folderPath = EspApplication.sharedInstance().getEspRootSDPath() + "bin/" + subPath;
        String fileName = null;
        if (isUser1)
        {
            fileName = USER1_BIN;
        }
        else
        {
            fileName = USER2_BIN;
        }
        try
        {
            FileInputStream fin = new FileInputStream(folderPath + "/" + fileName);
            int len = fin.available();
            byte[] buffer = new byte[len];
            fin.read(buffer);
            fin.close();
            log.debug(Thread.currentThread().toString() + "##__loadBinFromLocal(isUser1=[" + isUser1
                + "],latestVersion=[" + latestVersion + "]): " + "suc");
            return buffer;
        }
        catch (FileNotFoundException e)
        {
            e.printStackTrace();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        log.warn(Thread.currentThread().toString() + "##__loadBinFromLocal(isUser1=[" + isUser1 + "],latestVersion=["
            + latestVersion + "]): " + "fail");
        return null;
    }
    
    private static boolean __isHttpsSupported()
    {
        SharedPreferences sp =
            EspApplication.sharedInstance().getSharedPreferences(EspStrings.Key.SYSTEM_CONFIG, Context.MODE_PRIVATE);
        return sp.getBoolean(EspStrings.Key.HTTPS_SUPPORT, true);
    }
    
    /**
     * download user bin from server
     * 
     * @param isUser1 user1.bin or user2.bin is returned
     * @return
     */
    private byte[] __loadBinFromInternet(boolean isUser1, String deviceKey, String latestRomVersion)
    {
        
        // download user1.bin
        String headerKey = Authorization;
        String headerValue = TOKEN + " " + deviceKey;
        String url = __getDownloadUrl(latestRomVersion, USER1_BIN);
        if (!__isHttpsSupported())
        {
            url = url.replace("https", "http");
        }
        HttpGet httpGet = new HttpGet(url);
        httpGet.addHeader(headerKey, headerValue);
        IEspDeviceUpgradeParser parser = EspDeviceUpgradeParser.getInstance();
        IEspDeviceUpgradeInfo latestRomVersionInfo = parser.parseUpgradeInfo(latestRomVersion);
        String subPath = Long.toString(latestRomVersionInfo.getIdValue());
        String folderPath = EspApplication.sharedInstance().getEspRootSDPath() + "bin/" + subPath;
        String saveName = USER1_BIN;
        boolean suc = __download(httpGet, folderPath, saveName);
        if (suc)
        {
            log.debug(Thread.currentThread().toString() + "##__loadBinFromInternet(isUser1=[" + isUser1
                + "],deviceKey=[" + deviceKey + "],latestRomVersion=[" + latestRomVersion + "]): " + "suc");
        }
        else
        {
            log.warn(Thread.currentThread().toString() + "##__loadBinFromInternet(isUser1=[" + isUser1
                + "],deviceKey=[" + deviceKey + "],latestRomVersion=[" + latestRomVersion + "]): " + "fail");
            return null;
        }
        // download user2.bin
        saveName = USER2_BIN;
        url = __getDownloadUrl(latestRomVersion, USER2_BIN);
        if (!__isHttpsSupported())
        {
            url = url.replace("https", "http");
        }
        // the elder httpGet is aborted
        httpGet = new HttpGet(url);
        httpGet.addHeader(headerKey, headerValue);
        suc = __download(httpGet, folderPath, saveName);
        if (suc)
        {
            log.debug(Thread.currentThread().toString() + "##__loadBinFromInternet(isUser1=[" + isUser1
                + "],deviceKey=[" + deviceKey + "],latestRomVersion=[" + latestRomVersion + "]): " + "suc");
        }
        else
        {
            log.warn(Thread.currentThread().toString() + "##__loadBinFromInternet(isUser1=[" + isUser1
                + "],deviceKey=[" + deviceKey + "],latestRomVersion=[" + latestRomVersion + "]): " + "fail");
            return null;
        }
        // get user1.bin or user2.bin from local file system
        return __loadBinFromLocal(isUser1, latestRomVersion);
    }
    
    /**
     * get user1.bin or user2.bin
     * 
     * @param isUser1 true means to get user1.bin
     * @param deviceKey the device's key, if user1.bin or user2.bin can't found local, user1.bin and user2.bin will be
     *            downloaded by Internet
     * @param latestRomVersion the device's latest rom version
     * @return the binary of user1.bin or user2.bin
     */
    private byte[] __getUserBin(boolean isUser1, String deviceKey, String latestRomVersion)
    {
        byte[] result = __loadBinFromLocal(isUser1, latestRomVersion);
        if (result != null)
        {
            log.debug(Thread.currentThread().toString() + "##__getUserBin(isUser1=[" + isUser1 + "],deviceKey=["
                + deviceKey + "],latestRomVersion=[" + latestRomVersion + "])1: " + result);
            return result;
        }
        else
        {
            __deleteDownloadIdValue(latestRomVersion);
            result = __loadBinFromInternet(isUser1, deviceKey, latestRomVersion);
            if (result != null)
            {
                __saveDownloadIdValue(latestRomVersion);
            }
            log.debug(Thread.currentThread().toString() + "##__getUserBin(isUser1=[" + isUser1 + "],deviceKey=["
                + deviceKey + "],latestRomVersion=[" + latestRomVersion + "])2: " + result);
            return result;
        }
    }
    
    private boolean __postUpgradeStart(InetAddress inetAddress)
    {
        String url = __getStartUrl(inetAddress);
        HttpPost httpPost = new HttpPost(url);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        __setHttpClientParams(httpClient);
        try
        {
            int result = httpClient.execute(httpPost).getStatusLine().getStatusCode();
            if (result == HttpStatus.SC_OK)
            {
                log.debug(Thread.currentThread().toString() + "##__postUpgradeStart(inetAddress=[" + inetAddress
                    + "]): " + true);
                return true;
            }
        }
        catch (ClientProtocolException e)
        {
            log.warn(Thread.currentThread().toString() + "##__postUpgradeStart(inetAddress=[" + inetAddress + "])1: "
                + false);
            return false;
        }
        catch (IOException e)
        {
            log.warn(Thread.currentThread().toString() + "##__postUpgradeStart(inetAddress=[" + inetAddress + "])2: "
                + false);
            return false;
        }
        finally
        {
            httpClient.getConnectionManager().shutdown();
        }
        log.warn(Thread.currentThread().toString() + "##__postUpgradeStart(inetAddress=[" + inetAddress + "])3: "
            + false);
        return false;
    }
    
    /**
     * push the user1.bin or user2.bin to the device by local
     * 
     * @param inetAddress the ip address
     * @param isUser1 true means user1.bin will be pushed
     * @param userBin the byte[] of user1.bin or user2.bin
     * @return whether the user1.bin or user2.bin is pushed to device suc
     */
    private boolean __pushUserBin(InetAddress inetAddress, boolean isUser1, byte[] userBin)
    {
        ByteArrayEntity arrayEntity = new ByteArrayEntity(userBin);
        arrayEntity.setContentType("application/octet-stream");
        String url = __getUpgradePushUrl(inetAddress, isUser1);
        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(arrayEntity);
        DefaultHttpClient httpClient = new DefaultHttpClient();
        __setHttpClientParams(httpClient);
        try
        {
            int result = httpClient.execute(httpPost).getStatusLine().getStatusCode();
            if (result == HttpStatus.SC_OK)
            {
                log.debug(Thread.currentThread().toString() + "##__pushUserBin(inetAddress=[" + inetAddress
                    + "],isUser1=[" + isUser1 + "],userBin=[" + userBin + "]): " + true);
                return true;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            httpClient.getConnectionManager().shutdown();
        }
        log.warn(Thread.currentThread().toString() + "##__pushUserBin(inetAddress=[" + inetAddress + "],isUser1=["
            + isUser1 + "],userBin=[" + userBin + "]): " + false);
        return false;
    }
    
    /**
     * reboot the device to use new bin
     * 
     * @param inetAddress the ip address
     * @return whether the device is reboot
     */
    private boolean __reset(InetAddress inetAddress)
    {
        log.debug(Thread.currentThread().toString() + "##__reset(inetAddress=[" + inetAddress + "]): " + true);
        String url = __getResetUrl(inetAddress);
        EspBaseApiUtil.Post(url, null);
        /**
         * for some reason, after receiving reset command, the device will reboot and phone won't get response from
         * device. in almost all of situations, the reset command will suc, so return true forever
         */
        return true;
    }
    
    /**
     * get device's IOTAddress by UDP broadcast
     * 
     * @param bssid the device's bssid
     * @return the device's InetAddress or null
     */
    private IOTAddress __getIOTAddress(String bssid)
    {
        IOTAddress iotAddress = null;
        for (int retry = 0; retry < 10; retry++)
        {
            iotAddress = EspBaseApiUtil.discoverDevice(bssid);
            if (iotAddress != null)
            {
                return iotAddress;
            }
        }
        return null;
    }
    
    private boolean __doUpgradeLocal(InetAddress inetAddress, String bssid, String deviceKey, String latestRomVersion,String router)
    {
        Boolean isUser1 = __isUser1Running(inetAddress);
        boolean suc = false;
        if (isUser1 == null)
        {
            log.warn(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress
                + "],deviceKey=[" + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion
                + "]): " + "__isUser1Running() fail");
            return false;
        }
        byte[] userBin = __getUserBin(!isUser1, deviceKey, latestRomVersion);
        if (userBin == null)
        {
            log.warn(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress
                + "],deviceKey=[" + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion
                + "]): " + "__getUserBin() fail");
            return false;
        }
        suc = __postUpgradeStart(inetAddress);
        if (!suc)
        {
            log.warn(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress
                + "],deviceKey=[" + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion
                + "]): " + "__postUpgradeStart() fail");
            return false;
        }
        suc = __pushUserBin(inetAddress, isUser1, userBin);
        if (!suc)
        {
            log.warn(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress
                + "],deviceKey=[" + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion
                + "]): " + "__pushUserBin() fail");
            return false;
        }
        suc = __reset(inetAddress);
        if (!suc)
        {
            log.warn(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress
                + "],deviceKey=[" + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion
                + "]): " + "__reset() fail");
            return false;
        }
        return true;
    }
    
    @Override
    public IOTAddress doUpgradeLocal(InetAddress inetAddress, String bssid, String deviceKey, String latestRomVersion)
    {
        if (!__doUpgradeLocal(inetAddress, bssid, deviceKey, latestRomVersion, null))
        {
            return null;
        }
        IOTAddress iotAddressResult = __getIOTAddress(bssid);
        if (iotAddressResult == null)
        {
            log.warn(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress
                + "],deviceKey=[" + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion
                + "]): " + "__getInetAddress() fail");
            return null;
        }
        log.info(Thread.currentThread().toString() + "##doUpgradeLocal(inetAddress=[" + inetAddress + "],deviceKey=["
            + deviceKey + "],bssid=[" + bssid + "],latestRomVersion=[" + latestRomVersion + "]): " + iotAddressResult);
        return iotAddressResult;
    }

    private List<IOTAddress> __getIOTAddressList(String deviceBssid)
    {
        List<IOTAddress> result = EspBaseApiUtil.discoverDevices();
        for (IOTAddress iotAddress : result)
        {
            // if the result contain the specific device return the result,
            // else return null 
            if (iotAddress.getBSSID().equals(deviceBssid))
            {
                return result;
            }
        }
        return null;
    }
    
    @Override
    public List<IOTAddress> doUpgradeMeshDeviceLocal(InetAddress inetAddress, String bssid, String deviceKey,
        String latestRomVersion, String router)
    {
        log.debug(Thread.currentThread().toString() + "##doUpgradeMeshDeviceLocal(inetAddress=[" + inetAddress
            + "],bssid=[" + bssid + "],deviceKey=[" + deviceKey + "],latestRomVersion=[" + latestRomVersion
            + "],router=[" + router + "])");
        log.debug("doUpgradeMeshDeviceLocal get user1.bin start");
        // get user1.bin and user2.bin
        byte[] user1 = __getUserBin(true, deviceKey, latestRomVersion);
        if (user1 == null)
        {
            log.warn("doUpgradeMeshDeviceLocal get user1.bin fail");
            return null;
        }
        log.debug("doUpgradeMeshDeviceLocal get user1.bin suc");
        log.debug("doUpgradeMeshDeviceLocal get user2.bin start");
        byte[] user2 = __getUserBin(false, deviceKey, latestRomVersion);
        if (user2 == null)
        {
            log.warn("doUpgradeMeshDeviceLocal get user2.bin fail");
            return null;
        }
        log.debug("doUpgradeMeshDeviceLocal get user2.bin suc");
        // connect
        log.debug("doUpgradeMeshDeviceLocal connect start");
        EspSocketClient client = null;
        final int retryTime = 3;
        for (int retry = 0; client == null && retry < retryTime; retry++)
        {
            client = EspPureSocketNetUtil.connect(inetAddress);
        }
        if (client == null)
        {
            log.warn("doUpgradeMeshDeviceLocal connect fail");
            return null;
        }
        log.debug("doUpgradeMeshDeviceLocal connect suc");
        // send upgrade start
        log.debug("doUpgradeMeshDeviceLocal upgradeStart start");
        boolean isUpgradeStartSuc = false;
        for (int retry = 0; !isUpgradeStartSuc && retry < retryTime; retry++)
        {
            isUpgradeStartSuc =
                EspPureSocketNetUtil.executeMeshUpgradeLocalRequest(client, router, inetAddress, latestRomVersion);
        }
        if(!isUpgradeStartSuc)
        {
            log.warn("doUpgradeMeshDeviceLocal upgradeStart fail");
            return null;
        }
        log.debug("doUpgradeMeshDeviceLocal upgradeStart suc");
        // listen device request
        log.debug("doUpgradeMeshDeviceLocal listen start");
        final long timeout = 180 * 1000;
        boolean isListenSuc = EspPureSocketNetUtil.listen(client, user1, user2, inetAddress, router, bssid, timeout);
        if (!isListenSuc)
        {
            log.warn("doUpgradeMeshDeviceLocal listen fail");
            return null;
        }
        log.debug("doUpgradeMeshDeviceLocal listen suc");
        // discover current local device list
        log.debug("doUpgradeMeshDeviceLocal discover start");
        List<IOTAddress> result = null;
        for (int retry = 0; result == null && retry < retryTime; retry++)
        {
            result = __getIOTAddressList(bssid);
        }
        if(result==null)
        {
            log.warn("doUpgradeMeshDeviceLocal discover fail");
            return null;
        }
        log.debug("doUpgradeMeshDeviceLocal discover suc");
        return result;
    }
    
}
