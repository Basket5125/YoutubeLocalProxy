package com.viitube.proxy;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import fi.iki.elonen.NanoHTTPD;

/**
 * Usługa w tle: startuje serwer HTTP i (jeśli root dostępny) jednorazowo przekierowuje port 80 -> port
 * z configu przez iptables, żeby spatchowana appka YouTube mogła nadal celować w localhost:80.
 */
public class ProxyService extends Service {

    private ProxyServer server;
    private int listeningPort;

    @Override
    public void onCreate() {
        super.onCreate();

        HttpClient.initTls();
        Config config = Config.get(this);
        if (!config.getBool("proxy_enabled", true)) {
            stopSelf();
            return;
        }
        int port = config.getInt("port", 8080);
        listeningPort = port;

        try {
            server = new ProxyServer(port, this, config);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (config.getBool("redirect_port_80_via_root", true)) {
            redirectPort80(port);
        }

        Notification notification = new Notification.Builder(this)
                .setContentTitle("YouTube Proxy")
                .setContentText("Running on 127.0.0.1:" + port)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
        startForeground(1, notification);
    }

    private void redirectPort80(int targetPort) {
        try {
            String cmd = "iptables -t nat -A OUTPUT -p tcp --dport 80 -d 127.0.0.1 -j REDIRECT --to-port "
                    + targetPort;
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception e) {
            // brak roota / iptables niedostepny - appka i tak dziala na skonfigurowanym porcie
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        removePort80Redirect(listeningPort);
        super.onDestroy();
    }

    private void removePort80Redirect(int targetPort) {
        if (targetPort <= 0) return;
        try {
            String cmd = "iptables -t nat -D OUTPUT -p tcp --dport 80 -d 127.0.0.1 -j REDIRECT --to-port "
                    + targetPort;
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
