package com.viitube.proxy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import fi.iki.elonen.NanoHTTPD;

/**
 * Usługa w tle: startuje serwer HTTP i (jeśli root dostępny) jednorazowo przekierowuje port 80 -> port
 * z configu przez iptables, żeby spatchowana appka YouTube mogła nadal celować w localhost:80.
 */
public class ProxyService extends Service {

    private static final String NOTIFICATION_CHANNEL_ID = "proxy";
    private ProxyServer server;
    private int listeningPort;
    private boolean port80RedirectApplied;

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
            port80RedirectApplied = redirectPort80(port);
        }

        Notification notification = buildNotification(port);
        startForeground(1, notification);
    }

    private Notification buildNotification(int port) {
        String text = "Running on 127.0.0.1:" + port;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            Notification notification = new Notification(
                    android.R.drawable.ic_menu_info_details, "YouTube Proxy", System.currentTimeMillis());
            Intent launchIntent = new Intent(this, MainActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.notification_legacy);
            contentView.setImageViewResource(R.id.notification_icon,
                    android.R.drawable.ic_menu_info_details);
            contentView.setTextViewText(R.id.notification_title, "YouTube Proxy");
            contentView.setTextViewText(R.id.notification_text, text);
            notification.contentView = contentView;
            notification.contentIntent = contentIntent;
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            return notification;
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "YouTube Proxy", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("YouTube Proxy")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                ? builder.build() : builder.getNotification();
    }

    private boolean redirectPort80(int targetPort) {
        String cmd = "iptables -t nat -A OUTPUT -p tcp --dport 80 -d 127.0.0.1 -j REDIRECT --to-port "
                + targetPort;
        return runRootCommand(cmd, "add port 80 redirect");
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
        }
        if (port80RedirectApplied) removePort80Redirect(listeningPort);
        super.onDestroy();
    }

    private void removePort80Redirect(int targetPort) {
        if (targetPort <= 0) return;
        String cmd = "iptables -t nat -D OUTPUT -p tcp --dport 80 -d 127.0.0.1 -j REDIRECT --to-port "
                + targetPort;
        runRootCommand(cmd, "remove port 80 redirect");
    }

    private boolean runRootCommand(String command, String operation) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                android.util.Log.i("ProxyService", "Could not " + operation
                        + " (su/iptables exited with " + exitCode + "); proxy remains available on port "
                        + listeningPort);
                return false;
            }
            return true;
        } catch (java.io.IOException e) {
            android.util.Log.i("ProxyService", "Could not " + operation
                    + " (root access unavailable); proxy remains available on port " + listeningPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            android.util.Log.w("ProxyService", "Interrupted while attempting to " + operation, e);
        } catch (SecurityException e) {
            android.util.Log.i("ProxyService", "Could not " + operation
                    + " (command execution denied); proxy remains available on port " + listeningPort);
        } finally {
            if (process != null) process.destroy();
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
