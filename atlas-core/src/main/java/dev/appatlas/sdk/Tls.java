package dev.appatlas.sdk;

import android.os.Build;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS 1.2 on API 16–19, where the platform has it but ships it disabled.
 * This is the price of the minSdk the incumbents walked away from; from 20
 * up the platform default is already right and this class does nothing.
 */
final class Tls {

    private Tls() {
    }

    static void enableTls12() {
        if (Build.VERSION.SDK_INT >= 20) {
            return;
        }

        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(new Enabling(context.getSocketFactory()));
        } catch (GeneralSecurityException unavailable) {
            // A ROM without TLS 1.2 at all: requests proceed on the platform
            // default and fail at the server's door, visibly.
        }
    }

    /** Wraps every created socket to switch the protocol on. */
    private static final class Enabling extends SSLSocketFactory {

        private static final String[] PROTOCOLS = {"TLSv1.2"};

        private final SSLSocketFactory inner;

        Enabling(SSLSocketFactory inner) {
            this.inner = inner;
        }

        private static Socket enable(Socket socket) {
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).setEnabledProtocols(PROTOCOLS);
            }

            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return inner.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return inner.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return enable(inner.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enable(inner.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return enable(inner.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enable(inner.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return enable(inner.createSocket(host, port, local, localPort));
        }
    }
}
