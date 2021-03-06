package crypt.ssl.testing;


import crypt.ssl.utils.StreamUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

public class HttpComponentsTest {

    public static void main(String[] args) throws Exception {

        SSLContext sslcontext = SSLContexts.custom()
                .loadTrustMaterial(
                        /*loadKeyStore("tomcat.keystore", "123456"),*/
                        loadKeyStore(new File(CertificateTest.CA_CERTS), ""),
                        new TrustSelfSignedStrategy()
                )
                .build();

        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                sslcontext,
                null,
                null,
                SSLConnectionSocketFactory.getDefaultHostnameVerifier()
        );

        try (CloseableHttpClient httpclient = HttpClients.custom()
                .setSSLSocketFactory(socketFactory)
                .build()) {

            HttpGet httpget = new HttpGet("https://habrahabr.ru" /*"https://localhost:8090/test"*/);

            System.out.println("Executing request " + httpget.getRequestLine());

            try (CloseableHttpResponse response = httpclient.execute(httpget)) {
                HttpEntity entity = response.getEntity();

                System.out.println("----------------------------------------");
                System.out.println(response.getStatusLine());
                System.out.println(StreamUtils.copyToAsciiString(entity.getContent()));
            }
        }
    }

    public static KeyStore loadKeyStore(String resource, String storePassword) throws GeneralSecurityException, IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            return loadKeyStore(is, storePassword);
        }
    }

    public static KeyStore loadKeyStore(File file, String storePassword) throws GeneralSecurityException, IOException {
        try (InputStream is = new FileInputStream(file)) {
            return loadKeyStore(is, storePassword);
        }
    }

    public static KeyStore loadKeyStore(InputStream is, String storePassword) throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(is, storePassword.toCharArray());

        return trustStore;
    }
}
