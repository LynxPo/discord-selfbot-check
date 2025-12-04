import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class DiscordSelfBot {

    // ============================================
    // BIẾN MÔI TRƯỜNG - KHÔNG LƯU TRỰC TIẾP TRONG CODE
    // ============================================
    private static String USER_TOKEN;
    private static String CHANNEL_ID;
    private static int MIN_INTERVAL_MINUTES;
    private static int MAX_INTERVAL_MINUTES;
    private static List<String> MESSAGES = new ArrayList<>();

    // ============================================
    // CẤU HÌNH MẶC ĐỊNH - SẼ BỊ GHI ĐÈ BỞI ENV
    // ============================================
    private static final String DEFAULT_TOKEN = "CHANGE_ME";
    private static final String DEFAULT_CHANNEL_ID = "CHANGE_ME";
    private static final int DEFAULT_MIN_INTERVAL = 120;   // 2 giờ
    private static final int DEFAULT_MAX_INTERVAL = 135;   // 2h15p
    private static final String[] DEFAULT_MESSAGES = {
            "Hello from Railway Bot!",
            "Bot is running on cloud",
            "Automated message from server",
            "This is a scheduled message"
    };

    // ============================================
    // BIẾN HỆ THỐNG
    // ============================================
    private static volatile boolean isRunning = true;
    private static volatile int messageCount = 0;
    private static ScheduledExecutorService scheduler;
    private static ServerSocket healthCheckSocket;

    public static void main(String[] args) {
        try {
            System.out.println("==========================================");
            System.out.println("     Discord Self-Bot (Railway Edition)");
            System.out.println("==========================================\n");

            // Load cấu hình từ biến môi trường
            loadConfiguration();

            // Kiểm tra cấu hình
            if (!validateConfig()) {
                System.exit(1);
            }

            // Khởi động health check server
            startHealthCheckServer();

            // Khởi động bot
            startBot();

            // Xử lý shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Shutdown signal received...");
                stopBot();
            }));

            // Giữ chương trình chạy
            while (isRunning) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            System.err.println("❌ Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void loadConfiguration() {
        System.out.println("📋 Loading configuration from environment variables...");

        // Load từ biến môi trường (Railway sẽ inject)
        USER_TOKEN = System.getenv("DISCORD_USER_TOKEN");
        CHANNEL_ID = System.getenv("DISCORD_CHANNEL_ID");

        // Parse số từ env
        try {
            MIN_INTERVAL_MINUTES = Integer.parseInt(
                    System.getenv().getOrDefault("MIN_INTERVAL_MINUTES",
                            String.valueOf(DEFAULT_MIN_INTERVAL))
            );
            MAX_INTERVAL_MINUTES = Integer.parseInt(
                    System.getenv().getOrDefault("MAX_INTERVAL_MINUTES",
                            String.valueOf(DEFAULT_MAX_INTERVAL))
            );
        } catch (NumberFormatException e) {
            System.err.println("⚠️  Invalid interval values, using defaults");
            MIN_INTERVAL_MINUTES = DEFAULT_MIN_INTERVAL;
            MAX_INTERVAL_MINUTES = DEFAULT_MAX_INTERVAL;
        }

        // Load messages từ env (có thể là JSON array)
        String messagesEnv = System.getenv("DISCORD_MESSAGES");
        if (messagesEnv != null && !messagesEnv.trim().isEmpty()) {
            try {
                // Parse JSON đơn giản: ["msg1","msg2"]
                messagesEnv = messagesEnv.trim();
                if (messagesEnv.startsWith("[") && messagesEnv.endsWith("]")) {
                    String content = messagesEnv.substring(1, messagesEnv.length() - 1);
                    String[] messages = content.split("\",\"");
                    for (String msg : messages) {
                        msg = msg.replace("\"", "").trim();
                        if (!msg.isEmpty()) {
                            MESSAGES.add(msg);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("⚠️  Failed to parse messages from env, using defaults");
                MESSAGES.addAll(Arrays.asList(DEFAULT_MESSAGES));
            }
        }

        // Nếu không có messages từ env, dùng defaults
        if (MESSAGES.isEmpty()) {
            MESSAGES.addAll(Arrays.asList(DEFAULT_MESSAGES));
        }

        // Fallback nếu không có env
        if (USER_TOKEN == null || USER_TOKEN.isEmpty()) {
            USER_TOKEN = DEFAULT_TOKEN;
        }
        if (CHANNEL_ID == null || CHANNEL_ID.isEmpty()) {
            CHANNEL_ID = DEFAULT_CHANNEL_ID;
        }

        // Hiển thị cấu hình (ẩn token)
        System.out.println("✅ Configuration loaded:");
        System.out.println("   Token: " + maskToken(USER_TOKEN));
        System.out.println("   Channel ID: " + CHANNEL_ID);
        System.out.println("   Interval: " + MIN_INTERVAL_MINUTES + "-" + MAX_INTERVAL_MINUTES + " minutes");
        System.out.println("   Messages: " + MESSAGES.size() + " available");
    }

    private static boolean validateConfig() {
        System.out.println("\n🔍 Validating configuration...");

        // Kiểm tra token
        if (USER_TOKEN.equals(DEFAULT_TOKEN)) {
            System.err.println("❌ ERROR: Discord token not configured!");
            System.err.println("\nPlease set these environment variables in Railway:");
            System.err.println("1. DISCORD_USER_TOKEN - Your Discord user token");
            System.err.println("2. DISCORD_CHANNEL_ID - Target channel ID");
            System.err.println("\nOptional variables:");
            System.err.println("3. MIN_INTERVAL_MINUTES - Min interval (default: 120)");
            System.err.println("4. MAX_INTERVAL_MINUTES - Max interval (default: 135)");
            System.err.println("5. DISCORD_MESSAGES - JSON array of messages");
            return false;
        }

        // Kiểm tra Channel ID
        if (CHANNEL_ID.equals(DEFAULT_CHANNEL_ID)) {
            System.err.println("❌ ERROR: Channel ID not configured!");
            return false;
        }

        // Kiểm tra interval
        if (MIN_INTERVAL_MINUTES <= 0 || MAX_INTERVAL_MINUTES <= 0) {
            System.err.println("❌ ERROR: Interval must be positive!");
            return false;
        }

        if (MIN_INTERVAL_MINUTES > MAX_INTERVAL_MINUTES) {
            System.err.println("❌ ERROR: MIN cannot be greater than MAX!");
            return false;
        }

        // Test token (optional)
        System.out.println("Testing Discord connection...");
        if (!testDiscordConnection()) {
            System.err.println("⚠️  Warning: Discord connection test failed!");
            System.err.println("The bot will continue but may not work properly.");
            System.err.println("Please check your token and channel ID.");
        }

        return true;
    }

    private static boolean testDiscordConnection() {
        try {
            String apiUrl = "https://discord.com/api/v9/users/@me";
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", USER_TOKEN);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void startHealthCheckServer() {
        new Thread(() -> {
            try {
                healthCheckSocket = new ServerSocket(8080);
                System.out.println("✅ Health check server started on port 8080");

                while (isRunning) {
                    try (ServerSocket server = healthCheckSocket;
                         var socket = server.accept();
                         var out = new PrintWriter(socket.getOutputStream(), true)) {

                        out.println("HTTP/1.1 200 OK");
                        out.println("Content-Type: application/json");
                        out.println("Connection: close");
                        out.println();
                        out.println("{\"status\":\"healthy\",\"messages_sent\":" +
                                messageCount + ",\"running\":" + isRunning + "}");
                    } catch (Exception e) {
                        if (isRunning) {
                            System.err.println("Health check error: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to start health check: " + e.getMessage());
            }
        }).start();
    }

    private static void startBot() {
        System.out.println("\n🚀 Starting Discord bot...");

        scheduler = Executors.newScheduledThreadPool(1);

        // Gửi tin nhắn đầu tiên ngay
        sendMessage();

        // Lên lịch cho lần tiếp theo
        scheduleNextMessage();

        System.out.println("✅ Bot started successfully!");
        System.out.println("📊 Messages will be sent every " + MIN_INTERVAL_MINUTES +
                "-" + MAX_INTERVAL_MINUTES + " minutes");
    }

    private static void scheduleNextMessage() {
        if (!isRunning || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        // Random thời gian chờ
        int randomMinutes = ThreadLocalRandom.current().nextInt(
                MIN_INTERVAL_MINUTES,
                MAX_INTERVAL_MINUTES + 1
        );

        int randomSeconds = ThreadLocalRandom.current().nextInt(0, 60);
        long delayInSeconds = randomMinutes * 60L + randomSeconds;

        // Tính thời điểm gửi tiếp theo
        Calendar nextTime = Calendar.getInstance();
        nextTime.add(Calendar.SECOND, (int) delayInSeconds);
        String nextTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(nextTime.getTime());

        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date());

        System.out.println("\n[" + timestamp + "] ⏰ Next message at: " + nextTimeStr +
                " (in " + randomMinutes + "m " + randomSeconds + "s)");

        // Lên lịch
        scheduler.schedule(() -> {
            if (isRunning) {
                sendMessage();
                scheduleNextMessage();
            }
        }, delayInSeconds, TimeUnit.SECONDS);
    }

    private static void sendMessage() {
        try {
            messageCount++;
            String randomMessage = getRandomMessage();

            String apiUrl = "https://discord.com/api/v9/channels/" + CHANNEL_ID + "/messages";
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", USER_TOKEN);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            String jsonPayload = String.format("{\"content\":\"%s\"}",
                    escapeJson(randomMessage));

            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date());

            System.out.println("\n[" + timestamp + "] 📤 Sending message #" + messageCount);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 200 || responseCode == 201) {
                System.out.println("[" + timestamp + "] ✅ Message sent successfully!");

                // Log message preview
                String preview = randomMessage.length() > 50 ?
                        randomMessage.substring(0, 50) + "..." : randomMessage;
                System.out.println("   Preview: " + preview);

            } else if (responseCode == 401) {
                System.out.println("[" + timestamp + "] ❌ ERROR 401: Invalid token!");
                System.out.println("   Please update your DISCORD_USER_TOKEN in Railway.");

            } else if (responseCode == 403) {
                System.out.println("[" + timestamp + "] ❌ ERROR 403: No permission!");
                System.out.println("   Check channel ID and permissions.");

            } else if (responseCode == 429) {
                System.out.println("[" + timestamp + "] ⚠️  RATE LIMITED - Waiting 60s");
                Thread.sleep(60000);
                sendMessage(); // Retry
                return;

            } else {
                System.out.println("[" + timestamp + "] ❌ HTTP Error: " + responseCode);
            }

            conn.disconnect();

        } catch (Exception e) {
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss")
                    .format(new java.util.Date());
            System.out.println("[" + timestamp + "] ❌ Error: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private static void stopBot() {
        if (!isRunning) return;

        System.out.println("\n🛑 Stopping bot...");
        isRunning = false;

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }

        if (healthCheckSocket != null) {
            try {
                healthCheckSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        System.out.println("\n==========================================");
        System.out.println("          BOT STOPPED");
        System.out.println("          Total messages sent: " + messageCount);
        System.out.println("==========================================\n");
    }

    private static String getRandomMessage() {
        if (MESSAGES.isEmpty()) {
            return "Auto message #" + messageCount +
                    " (random: " + ThreadLocalRandom.current().nextInt(1000, 9999) + ")";
        }

        String msg = MESSAGES.get(ThreadLocalRandom.current().nextInt(MESSAGES.size()));
        msg = msg.replace("{random}", String.valueOf(ThreadLocalRandom.current().nextInt(1000, 9999)));
        msg = msg.replace("{count}", String.valueOf(messageCount));
        msg = msg.replace("{time}", new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date()));
        return msg;
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 10) return "***";
        return token.substring(0, 5) + "..." + token.substring(token.length() - 5);
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}