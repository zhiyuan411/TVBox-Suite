package com.github.catvod.net;

import android.os.Build;
import org.conscrypt.Conscrypt;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

public class SSLSocketFactoryCompat extends SSLSocketFactory {

    public static final HostnameVerifier hostnameVerifier = (hostname, session) -> true;

    public static final X509TrustManager trustAllCert = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}        

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}        

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[]{};
        }
    };

    static String[] protocols = null;
    static String[] cipherSuites = null;

    static {
        try {
            SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            if (socket != null) {
                List<String> protocols = new LinkedList<>();
                for (String protocol : socket.getSupportedProtocols()) if (!protocol.toUpperCase().contains("SSL")) protocols.add(protocol);
                SSLSocketFactoryCompat.protocols = protocols.toArray(new String[protocols.size()]);
                /* set up reasonable cipher suites */
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // choose known secure cipher suites
                    List<String> allowedCiphers = Arrays.asList(
                            // TLS 1.2
                            "TLS_RSA_WITH_AES_256_GCM_SHA384",
                            "TLS_RSA_WITH_AES_128_GCM_SHA256",
                            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                            "TLS_ECHDE_RSA_WITH_AES_128_GCM_SHA256",
                            // maximum interoperability
                            "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_RSA_WITH_AES_128_CBC_SHA",
                            // additionally                            
                            "TLS_RSA_WITH_AES_256_CBC_SHA",
                            "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                            "TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
                            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA");
                    List<String> availableCiphers = Arrays.asList(socket.getSupportedCipherSuites());
                    // take all allowed ciphers that are available and put them into preferredCiphers
                    HashSet<String> preferredCiphers = new HashSet<>(allowedCiphers);
                    preferredCiphers.retainAll(availableCiphers);
                    /* For maximum security, preferredCiphers should *replace* enabled ciphers (thus disabling
                     * ciphers which are enabled by default, but have become unsecure), but I guess for
                     * the security level of DAVdroid and maximum compatibility, disabling of insecure
                     * ciphers should be a server-side task */
                    // add preferred ciphers to enabled ciphers
                    HashSet<String> enabledCiphers = preferredCiphers;
                    enabledCiphers.addAll(new HashSet<>(Arrays.asList(socket.getEnabledCipherSuites())));
                    SSLSocketFactoryCompat.cipherSuites = enabledCiphers.toArray(new String[enabledCiphers.size()]);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final SSLSocketFactory defaultFactory;

    public SSLSocketFactoryCompat() {
        try {
            Provider provider = Conscrypt.newProvider();
            Security.insertProviderAt(provider, 1);
            SSLContext sslContext = SSLContext.getInstance("TLS", provider);
            sslContext.init(null, new X509TrustManager[]{trustAllCert}, null);
            defaultFactory = sslContext.getSocketFactory();
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultFactory);
        } catch (GeneralSecurityException e) {
            throw new AssertionError();
        }
    }

    private void upgradeTLS(SSLSocket ssl) {
        if (protocols != null) {
            ssl.setEnabledProtocols(protocols);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && cipherSuites != null) {
            ssl.setEnabledCipherSuites(cipherSuites);
        }
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return cipherSuites;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return cipherSuites;
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        Socket ssl = defaultFactory.createSocket(s, host, port, autoClose);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket ssl = defaultFactory.createSocket(host, port);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket ssl = defaultFactory.createSocket(host, port, localHost, localPort);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket ssl = defaultFactory.createSocket(host, port);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket ssl = defaultFactory.createSocket(address, port, localAddress, localPort);
        if (ssl instanceof SSLSocket) upgradeTLS((SSLSocket) ssl);
        return ssl;
    }
}