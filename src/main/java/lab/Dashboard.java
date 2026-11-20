package lab;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class Dashboard {
    record Coin(String name, String symbol, String id) {
        public String toString() { return name + " (" + symbol + ")"; }
    }

    static class Card extends JPanel {
        private final JLabel value = new JLabel("—");
        private final JLabel status = new JLabel();

        Card(String title) {
            super(new GridLayout(3, 1, 0, 4));
            setBackground(Color.WHITE);
            setBorder(new EmptyBorder(10, 18, 10, 18));
            add(new JLabel(title));
            value.setFont(value.getFont().deriveFont(Font.BOLD, 21f));
            add(value);
            status.setForeground(new Color(55, 76, 89));
            add(status);
        }

        void reset() {
            value.setText("—");
            status("Подключение…");
        }

        void status(String message) { status.setText(message); }

        void update(String text) {
            value.setText(text);
            status("Получено в " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        }
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Card price = new Card("Kraken   /   Последняя сделка");
    private final Card stats = new Card("Coinbase   /   Диапазон цен за 24 часа");
    private final Card profile = new Card("CoinPaprika   /   Профиль монеты");
    private Session session;

    private void show() {
        JFrame frame = new JFrame("Crypto Dashboard");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBorder(new EmptyBorder(16, 16, 16, 16));
        JPanel header = new JPanel(new BorderLayout(12, 0));
        JLabel title = new JLabel("Криптовалюты");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 27f));
        header.add(title, BorderLayout.WEST);
        JComboBox<Coin> coins = new JComboBox<>(new Coin[]{
                new Coin("Bitcoin", "BTC", "btc-bitcoin"), new Coin("Ethereum", "ETH", "eth-ethereum"),
                new Coin("Solana", "SOL", "sol-solana")});
        coins.getAccessibleContext().setAccessibleName("Криптовалюта");
        header.add(coins, BorderLayout.EAST);
        body.add(header, BorderLayout.NORTH);
        JPanel cards = new JPanel(new GridLayout(3, 1, 0, 10));
        cards.add(price);
        cards.add(stats);
        cards.add(profile);
        body.add(cards);
        frame.setContentPane(body);
        coins.addActionListener(e -> select((Coin) coins.getSelectedItem()));
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent event) { session.stop(); }
        });
        frame.setMinimumSize(new Dimension(600, 440));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        select(coins.getItemAt(0));
    }

    private void select(Coin coin) {
        if (session != null) session.stop();
        price.reset();
        stats.reset();
        profile.reset();
        session = new Session(client, coin, price, stats, profile);
        session.start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Dashboard().show());
    }
}
