package lab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

// Состояние выбранной монеты и таймеры принадлежат потоку событий Swing.
final class Session {
    private final HttpClient client;
    private final Dashboard.Coin coin;
    private final Dashboard.Card price, stats, profile;
    private final Set<Timer> timers = new HashSet<>();
    private final Set<CompletableFuture<?>> requests = new HashSet<>();
    private boolean stopped;
    private Feed feed;
    private int retrySeconds = 2;

    Session(HttpClient client, Dashboard.Coin coin, Dashboard.Card price,
            Dashboard.Card stats, Dashboard.Card profile) {
        this.client = client;
        this.coin = coin;
        this.price = price;
        this.stats = stats;
        this.profile = profile;
    }

    void start() {
        connect();
        poll("https://api.exchange.coinbase.com/products/" + coin.symbol() + "-USD/stats",
                stats, 30, json -> number(json, "low") + " — " + number(json, "high") + " USD");
        poll("https://api.coinpaprika.com/v1/coins/" + coin.id(), profile, 300,
                json -> json.get("name").getAsString() + "   ·   " + json.get("symbol").getAsString()
                        + "   ·   Место в рейтинге: " + json.get("rank").getAsInt());
    }

    private static String number(JsonObject json, String key) {
        return json.get(key).getAsBigDecimal().stripTrailingZeros().toPlainString();
    }

    private void later(int seconds, Runnable action) {
        Timer timer = new Timer(seconds * 1000, event -> {
            timers.remove((Timer) event.getSource());
            if (!stopped) action.run();
        });
        timer.setRepeats(false);
        timers.add(timer);
        timer.start();
    }

    private void poll(String url, Dashboard.Card card, int interval, Function<JsonObject, String> format) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "CryptoDashboardLab/1.0").GET().build();
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        requests.add(response);
        response.thenApply(reply -> {
            if (reply.statusCode() != 200) throw new IllegalStateException("HTTP " + reply.statusCode());
            return format.apply(JsonParser.parseString(reply.body()).getAsJsonObject());
        }).whenCompleteAsync((value, error) -> {
            requests.remove(response);
            if (stopped) return;
            int delay = interval;
            if (error == null) {
                card.update(value);
            } else {
                delay = 30;
                card.status("Ошибка: " + reason(error) + ". Повтор через " + delay + " с");
            }
            later(delay, () -> poll(url, card, interval, format));
        }, SwingUtilities::invokeLater);
    }

    private static String reason(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void connect() {
        price.status("Подключение к Kraken…");
        Feed attempt = new Feed();
        feed = attempt;
        var opening = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create("wss://ws.kraken.com/v2"), attempt);
        requests.add(opening);
        opening.whenCompleteAsync((socket, error) -> {
            requests.remove(opening);
            if (error != null) attempt.failed(reason(error));
        }, SwingUtilities::invokeLater);
    }

    private final class Feed implements WebSocket.Listener {
        private final StringBuilder fragments = new StringBuilder();
        private WebSocket socket;

        public void onOpen(WebSocket ws) {
            SwingUtilities.invokeLater(() -> {
                if (feed != this) { ws.abort(); return; }
                socket = ws;
                String subscribe = """
                        {"method":"subscribe","params":{"channel":"ticker","symbol":["%s/USD"]}}
                        """.formatted(coin.symbol());
                ws.sendText(subscribe, true).whenCompleteAsync((ignored, error) -> {
                    if (error != null) failed(reason(error));
                }, SwingUtilities::invokeLater);
                ws.request(1);
            });
        }

        public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            fragments.append(text);
            if (!last) {
                ws.request(1);
                return null;
            }
            String message = fragments.toString();
            fragments.setLength(0);
            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                if (json.has("success") && !json.get("success").getAsBoolean())
                    throw new IllegalStateException(json.get("error").getAsString());
                if (json.has("channel") && "ticker".equals(json.get("channel").getAsString())) {
                    JsonObject tick = json.getAsJsonArray("data").get(0).getAsJsonObject();
                    String value = number(tick, "last") + " USD";
                    SwingUtilities.invokeLater(() -> {
                        if (feed == this) {
                            retrySeconds = 2;
                            price.update(value);
                            ws.request(1);
                        }
                    });
                    return null;
                }
                ws.request(1);
            } catch (RuntimeException error) {
                SwingUtilities.invokeLater(() -> failed(reason(error)));
            }
            return null;
        }

        public CompletionStage<?> onClose(WebSocket ws, int code, String message) {
            SwingUtilities.invokeLater(() -> failed("код " + code));
            return null;
        }

        public void onError(WebSocket ws, Throwable error) {
            SwingUtilities.invokeLater(() -> failed(reason(error)));
        }

        private void failed(String message) {
            if (feed != this) return;
            feed = null;
            if (socket != null) socket.abort();
            price.status("Связь прервана: " + message + ". Повтор через " + retrySeconds + " с");
            later(retrySeconds, Session.this::connect);
            retrySeconds = Math.min(30, retrySeconds * 2);
        }
    }

    void stop() {
        stopped = true;
        for (Timer timer : timers) timer.stop();
        timers.clear();
        if (feed != null && feed.socket != null) feed.socket.abort();
        feed = null;
        for (var request : requests) request.cancel(true);
        requests.clear();
    }
}
