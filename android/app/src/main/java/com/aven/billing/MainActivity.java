package com.aven.billing;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://aven-billing-app.vercel.app/";
    private static final String PREFS = "aven_printer";
    private static final String KEY_ADDRESS = "printer_address";
    private static final String KEY_NAME = "printer_name";
    private static final String KEY_AUTO = "auto_print";
    private static final String KEY_LAST_AUTO = "last_auto_bill";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQUEST_BT_CONNECT = 701;

    private WebView webView;
    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor();

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildShell();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(Color.rgb(0, 96, 57));

        TextView title = new TextView(this);
        title.setText("AVEN");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button printer = new Button(this);
        printer.setText("Printer setup");
        printer.setTextSize(12);
        printer.setAllCaps(false);
        printer.setOnClickListener(v -> showPrinterSettings());
        bar.addView(printer, new LinearLayout.LayoutParams(dp(126), dp(42)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new PrinterBridge(), "AvenPrinter");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equalsIgnoreCase(uri.getScheme()) && "aven-billing-app.vercel.app".equalsIgnoreCase(uri.getHost())) return false;
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                }
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if ("https".equalsIgnoreCase(uri.getScheme()) && "aven-billing-app.vercel.app".equalsIgnoreCase(uri.getHost())) return false;
                if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                }
                return true;
            }
        });

        root.addView(bar);
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        webView.loadUrl(APP_URL);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isPrinterConfigured() {
        return !prefs().getString(KEY_ADDRESS, "").isEmpty();
    }

    private void showPrinterSettings() {
        String name = prefs().getString(KEY_NAME, "");
        boolean automatic = prefs().getBoolean(KEY_AUTO, true);
        String status = name.isEmpty() ? "No printer selected" : "Selected printer: " + name;
        String[] actions = new String[] {
            "Choose a paired printer",
            "Pair a new printer",
            automatic ? "Turn automatic printing OFF" : "Turn automatic printing ON",
            "Print a test receipt"
        };
        new AlertDialog.Builder(this)
            .setTitle("AVEN printer settings")
            .setMessage(status + "\nAutomatic printing: " + (automatic ? "ON" : "OFF") + "\n\nPair the printer in Android Bluetooth settings first.")
            .setItems(actions, (dialog, which) -> {
                if (which == 0) showPairedPrinters();
                else if (which == 1) startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                else if (which == 2) {
                    if (!isPrinterConfigured() && !automatic) {
                        Toast.makeText(this, "Choose a printer first.", Toast.LENGTH_LONG).show();
                    } else {
                        prefs().edit().putBoolean(KEY_AUTO, !automatic).apply();
                        Toast.makeText(this, "Automatic printing " + (!automatic ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                    }
                } else if (which == 3) printTestReceipt();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPairedPrinters() {
        if (!hasBluetoothConnectPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT_CONNECT);
            }
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This tablet does not support Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            new AlertDialog.Builder(this)
                .setTitle("Turn on Bluetooth")
                .setMessage("Turn on Bluetooth, pair your receipt printer, then return to AVEN.")
                .setPositiveButton("Bluetooth settings", (d, w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("Cancel", null).show();
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("No paired printers found")
                .setMessage("Open Bluetooth settings, pair the receipt printer (use the PIN supplied with the printer), then return and choose it here.")
                .setPositiveButton("Open Bluetooth settings", (d, w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("Cancel", null).show();
            return;
        }
        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        Collections.sort(devices, (a, b) -> safeDeviceName(a).compareToIgnoreCase(safeDeviceName(b)));
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);
            labels[i] = safeDeviceName(device) + "  (" + device.getAddress() + ")";
        }
        new AlertDialog.Builder(this)
            .setTitle("Select receipt printer")
            .setItems(labels, (d, which) -> {
                BluetoothDevice chosen = devices.get(which);
                prefs().edit()
                    .putString(KEY_ADDRESS, chosen.getAddress())
                    .putString(KEY_NAME, safeDeviceName(chosen))
                    .putBoolean(KEY_AUTO, true)
                    .apply();
                Toast.makeText(this, safeDeviceName(chosen) + " selected. Automatic printing is ON.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Bluetooth printer" : name;
        } catch (SecurityException e) {
            return "Bluetooth printer";
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) showPairedPrinters();
            else Toast.makeText(this, "AVEN needs Bluetooth permission to print receipts.", Toast.LENGTH_LONG).show();
        }
    }

    private void printTestReceipt() {
        if (!isPrinterConfigured()) {
            Toast.makeText(this, "Choose a paired printer first.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject test = new JSONObject();
            test.put("billNumber", "TEST-001");
            test.put("date", new java.util.Date().toString());
            test.put("outlet", "AVEN TEST");
            test.put("employee", "Printer setup");
            test.put("orderType", "Test receipt");
            test.put("subtotal", 10000);
            test.put("discount", 0);
            test.put("tax", 0);
            test.put("total", 10000);
            test.put("paid", 10000);
            test.put("change", 0);
            test.put("paperWidth", "80");
            JSONArray items = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("name", "Thermal printer test");
            item.put("qty", 1);
            item.put("amount", 10000);
            items.put(item);
            test.put("items", items);
            JSONArray payments = new JSONArray();
            JSONObject payment = new JSONObject();
            payment.put("method", "cash");
            payment.put("amount", 10000);
            payments.put(payment);
            test.put("payments", payments);
            queuePrint(test, false);
        } catch (Exception e) {
            Toast.makeText(this, "Could not create test receipt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void queuePrint(JSONObject bill, boolean automatic) {
        if (!hasBluetoothConnectPermission()) {
            reportPrinterStatus(false, "Allow Bluetooth permission, then retry printing.");
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT_CONNECT);
                }
            });
            return;
        }
        String address = prefs().getString(KEY_ADDRESS, "");
        if (address.isEmpty()) {
            reportPrinterStatus(false, "No printer selected. Tap Printer setup at the top of AVEN.");
            return;
        }
        String billKey = bill.optString("billNumber", "unknown") + "|" + bill.optString("date", "");
        if (automatic) {
            String last = prefs().getString(KEY_LAST_AUTO, "");
            if (billKey.equals(last)) return;
            prefs().edit().putString(KEY_LAST_AUTO, billKey).apply();
        }
        printExecutor.execute(() -> {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is turned off.");
                BluetoothDevice device = adapter.getRemoteDevice(address);
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                try {
                    socket.connect();
                    OutputStream output = socket.getOutputStream();
                    output.write(buildEscPosJob(bill));
                    output.flush();
                    try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } finally {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                reportPrinterStatus(true, automatic ? "Receipt printed automatically." : "Receipt sent to printer.");
            } catch (Exception e) {
                reportPrinterStatus(false, "Printing failed: " + (e.getMessage() == null ? "Check printer connection." : e.getMessage()) + " You can retry with Print / Reprint.");
            }
        });
    }

    private byte[] buildEscPosJob(JSONObject bill) throws Exception {
        int width = "58".equals(bill.optString("paperWidth")) ? 384 : 576;
        int pad = width == 384 ? 14 : 22;
        Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
        measure.setTextSize(width == 384 ? 19 : 23);
        float maxText = width - pad * 2;
        List<ReceiptLine> lines = new ArrayList<>();

        lines.add(ReceiptLine.center("AVEN", 36, true));
        lines.add(ReceiptLine.center("PAYMENT RECEIPT", 19, true));
        lines.add(ReceiptLine.center(" "));
        lines.add(ReceiptLine.pair("Bill No.", bill.optString("billNumber")));
        lines.add(ReceiptLine.pair("Date", bill.optString("date")));
        lines.add(ReceiptLine.pair("Outlet", bill.optString("outlet", "AVEN")));
        lines.add(ReceiptLine.pair("Staff", bill.optString("employee", "Staff")));
        lines.add(ReceiptLine.pair("Order", bill.optString("orderType", "")));
        String customer = bill.optString("customer", "");
        if (!customer.isEmpty()) lines.add(ReceiptLine.pair("Customer", customer));
        String phone = bill.optString("phone", "");
        if (!phone.isEmpty()) lines.add(ReceiptLine.pair("Phone", phone));
        lines.add(ReceiptLine.center("--------------------------------"));
        lines.add(ReceiptLine.pair("ITEM", "AMOUNT"));

        JSONArray items = bill.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String label = item.optString("name", "Item") + " x " + item.optString("qty", "1");
                lines.add(ReceiptLine.pair(label, formatMoney(item.optLong("amount", 0))));
            }
        }
        lines.add(ReceiptLine.center("--------------------------------"));
        lines.add(ReceiptLine.pair("Subtotal", formatMoney(bill.optLong("subtotal", 0))));
        double pct = bill.optDouble("discountPercent", 0);
        if (pct > 0) lines.add(ReceiptLine.pair("Discount (" + pct + "%)", "-" + formatMoney(bill.optLong("discount", 0))));
        lines.add(ReceiptLine.pair("Tax", formatMoney(bill.optLong("tax", 0))));
        lines.add(ReceiptLine.pair("TOTAL", formatMoney(bill.optLong("total", 0))).bold());
        JSONArray payments = bill.optJSONArray("payments");
        if (payments != null && payments.length() > 0) {
            for (int i = 0; i < payments.length(); i++) {
                JSONObject payment = payments.optJSONObject(i);
                if (payment != null) lines.add(ReceiptLine.pair(payment.optString("method", "payment").toUpperCase(), formatMoney(payment.optLong("amount", 0))));
            }
        } else {
            lines.add(ReceiptLine.pair("Payment pending", "-"));
        }
        long due = Math.max(0, bill.optLong("total", 0) - bill.optLong("paid", 0));
        lines.add(ReceiptLine.center("--------------------------------"));
        lines.add(ReceiptLine.pair("Amount due", formatMoney(due)));
        lines.add(ReceiptLine.pair("Change due", formatMoney(bill.optLong("change", 0))));
        lines.add(ReceiptLine.center(" "));
        lines.add(ReceiptLine.center("Thank you for choosing AVEN.", 20, false));
        lines.add(ReceiptLine.center(" "));

        List<ReceiptLine> wrapped = new ArrayList<>();
        for (ReceiptLine line : lines) {
            if (line.right != null) {
                if (measure.measureText(line.left) + measure.measureText(line.right) + 18 <= maxText) wrapped.add(line);
                else {
                    for (String part : wrapText(line.left, measure, maxText)) wrapped.add(ReceiptLine.left(part, line.size, line.bold));
                    for (String part : wrapText(line.right, measure, maxText)) wrapped.add(ReceiptLine.left(part, line.size, false));
                }
            } else if (line.center) {
                for (String part : wrapText(line.left, measure, maxText)) wrapped.add(ReceiptLine.center(part, line.size, line.bold));
            } else {
                for (String part : wrapText(line.left, measure, maxText)) wrapped.add(ReceiptLine.left(part, line.size, line.bold));
            }
        }

        int height = pad + 18;
        for (ReceiptLine line : wrapped) height += (int)(line.size * 1.45f) + 8;
        height += pad;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        int y = pad;
        for (ReceiptLine line : wrapped) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setTextSize(line.size);
            p.setTypeface(Typeface.create("sans-serif", line.bold ? Typeface.BOLD : Typeface.NORMAL));
            float baseline = y - p.getFontMetrics().top;
            if (line.center) {
                canvas.drawText(line.left, (width - p.measureText(line.left)) / 2f, baseline, p);
            } else if (line.right != null) {
                canvas.drawText(line.left, pad, baseline, p);
                canvas.drawText(line.right, width - pad - p.measureText(line.right), baseline, p);
            } else {
                canvas.drawText(line.left, pad, baseline, p);
            }
            y += (int)(line.size * 1.45f) + 8;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x1B, 0x40});
        int bytesPerRow = width / 8;
        int chunkRows = 128;
        for (int top = 0; top < height; top += chunkRows) {
            int rows = Math.min(chunkRows, height - top);
            out.write(new byte[]{0x1D, 0x76, 0x30, 0x00, (byte)(bytesPerRow & 0xFF), (byte)((bytesPerRow >> 8) & 0xFF), (byte)(rows & 0xFF), (byte)((rows >> 8) & 0xFF)});
            byte[] raster = new byte[bytesPerRow * rows];
            int index = 0;
            for (int row = 0; row < rows; row++) {
                for (int colByte = 0; colByte < bytesPerRow; colByte++) {
                    int value = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int pixel = bitmap.getPixel(colByte * 8 + bit, top + row);
                        int luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
                        if (luminance < 160) value |= (0x80 >> bit);
                    }
                    raster[index++] = (byte)value;
                }
            }
            out.write(raster);
        }
        bitmap.recycle();
        out.write(new byte[]{0x1B, 0x64, 0x04});
        out.write(new byte[]{0x1D, 0x56, 0x00});
        return out.toByteArray();
    }

    private String formatMoney(long paise) {
        return "Rs. " + String.format(java.util.Locale.US, "%.2f", paise / 100.0);
    }

    private List<String> wrapText(String text, Paint paint, float maxWidth) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) { result.add(" "); return result; }
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (paint.measureText(candidate) <= maxWidth || current.length() == 0) {
                current.setLength(0);
                current.append(candidate);
            } else {
                result.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private void reportPrinterStatus(boolean ok, String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (webView != null) {
                String js = "if(window.avenPrinterResult)window.avenPrinterResult(" + ok + "," + JSONObject.quote(message) + ");";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private class PrinterBridge {
        @JavascriptInterface public String isPrinterConfigured() {
            return MainActivity.this.isPrinterConfigured() ? "true" : "false";
        }
        @JavascriptInterface public String getAutoPrintEnabled() {
            return prefs().getBoolean(KEY_AUTO, true) ? "true" : "false";
        }
        @JavascriptInterface public void openPrinterSettings() {
            runOnUiThread(MainActivity.this::showPrinterSettings);
        }
        @JavascriptInterface public String printReceipt(String json) {
            try {
                JSONObject bill = new JSONObject(json);
                queuePrint(bill, bill.optBoolean("automatic", false));
                return "QUEUED";
            } catch (Exception e) {
                reportPrinterStatus(false, "Receipt data could not be read.");
                return "ERROR";
            }
        }
    }

    private static class ReceiptLine {
        final String left;
        final String right;
        final boolean center;
        final boolean bold;
        final int size;

        private ReceiptLine(String left, String right, boolean center, boolean bold, int size) {
            this.left = left;
            this.right = right;
            this.center = center;
            this.bold = bold;
            this.size = size;
        }
        static ReceiptLine center(String text) { return new ReceiptLine(text, null, true, false, 21); }
        static ReceiptLine center(String text, int size, boolean bold) { return new ReceiptLine(text, null, true, bold, size); }
        static ReceiptLine pair(String left, String right) { return new ReceiptLine(left, right, false, false, 21); }
        static ReceiptLine left(String text, int size, boolean bold) { return new ReceiptLine(text, null, false, bold, size); }
        ReceiptLine bold() { return new ReceiptLine(left, right, center, true, Math.max(size, 24)); }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) webView.destroy();
        printExecutor.shutdown();
        super.onDestroy();
    }
}
