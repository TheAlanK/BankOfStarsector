package com.bankofstarsector.ui;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.Str;
import com.nexusui.api.NexusPage;
import com.nexusui.bridge.GameDataBridge;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * NexusUI dashboard page.
 *
 * Threading: NexusUI calls {@link #refresh()} from its own "NexusUI-Refresh" thread and builds
 * the panel on the Swing thread. This page therefore never touches game state: it renders
 * {@link BankSnapshot} (captured on the game thread) and sends actions back through
 * NexusUI's GameDataBridge command queue, which executes them on the game thread.
 */
public class BankingNexusPage implements NexusPage {

    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color DARK_NAVY = new Color(20, 30, 60);
    private static final Color CARD_BG = new Color(30, 35, 50);
    private static final Color TEXT_PRIMARY = new Color(220, 220, 220);
    private static final Color TEXT_SECONDARY = new Color(160, 160, 160);
    private static final Color POSITIVE = new Color(100, 200, 100);
    private static final Color NEGATIVE = new Color(220, 80, 80);

    private JPanel mainPanel;
    private JLabel dateLabel;
    private JLabel netWorthLabel;
    private JLabel creditsLabel;
    private JLabel debtLabel;
    private JLabel investLabel;
    private JLabel dueLabel;
    private JLabel pastDueLabel;
    private JLabel autopayLabel;
    private JLabel scoreLabel;
    private JLabel bracketLabel;
    private JPanel loansPanel;
    private JPanel investmentsPanel;
    private JLabel warSurchargeLabel;
    private JLabel disruptionLabel;
    private JLabel sovereignLabel;
    private JButton payButton;
    private JButton autopayButton;
    private volatile BankSnapshot shown;

    @Override
    public String getId() { return "pbc_banking"; }

    @Override
    public String getTitle() { return Str.get("nexus.title"); }

    @Override
    public JPanel createPanel(int port) {
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(DARK_NAVY);
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel header = new JLabel(Str.get("nexus.header"));
        header.setFont(new Font("SansSerif", Font.BOLD, 16));
        header.setForeground(GOLD);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(header);
        dateLabel = new JLabel(Str.get("nexus.waiting"));
        dateLabel.setForeground(TEXT_SECONDARY);
        dateLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        dateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(dateLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        JPanel overviewCard = createCard(Str.get("nexus.card.overview"));
        netWorthLabel = addLabelRow(overviewCard, Str.get("nexus.netWorth"), "--");
        creditsLabel = addLabelRow(overviewCard, Str.get("nexus.credits"), "--");
        debtLabel = addLabelRow(overviewCard, Str.get("nexus.debt"), "--");
        investLabel = addLabelRow(overviewCard, Str.get("nexus.investments"), "--");
        dueLabel = addLabelRow(overviewCard, Str.get("nexus.dueNow"), "--");
        pastDueLabel = addLabelRow(overviewCard, Str.get("nexus.pastDue"), "--");
        autopayLabel = addLabelRow(overviewCard, Str.get("nexus.autopay"), "--");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        actions.setBackground(CARD_BG);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        payButton = new JButton(Str.get("nexus.payAll"));
        payButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { sendPayAllDue(); }
        });
        autopayButton = new JButton(Str.get("nexus.autopayOn"));
        autopayButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { sendToggleAutopay(); }
        });
        actions.add(payButton);
        actions.add(Box.createHorizontalStrut(6));
        actions.add(autopayButton);
        overviewCard.add(actions);
        mainPanel.add(overviewCard);
        mainPanel.add(Box.createVerticalStrut(8));

        JPanel scoreCard = createCard(Str.get("nexus.card.score"));
        scoreLabel = addLabelRow(scoreCard, Str.get("nexus.score"), "--");
        bracketLabel = addLabelRow(scoreCard, Str.get("nexus.bracket"), "--");
        mainPanel.add(scoreCard);
        mainPanel.add(Box.createVerticalStrut(8));

        JPanel loansCard = createCard(Str.get("nexus.card.loans"));
        loansPanel = new JPanel();
        loansPanel.setLayout(new BoxLayout(loansPanel, BoxLayout.Y_AXIS));
        loansPanel.setBackground(CARD_BG);
        loansCard.add(loansPanel);
        mainPanel.add(loansCard);
        mainPanel.add(Box.createVerticalStrut(8));

        JPanel investCard = createCard(Str.get("nexus.card.investments"));
        investmentsPanel = new JPanel();
        investmentsPanel.setLayout(new BoxLayout(investmentsPanel, BoxLayout.Y_AXIS));
        investmentsPanel.setBackground(CARD_BG);
        investCard.add(investmentsPanel);
        mainPanel.add(investCard);
        mainPanel.add(Box.createVerticalStrut(8));

        JPanel conditionsCard = createCard(Str.get("nexus.card.sector"));
        warSurchargeLabel = addLabelRow(conditionsCard, Str.get("nexus.war"), "0%");
        disruptionLabel = addLabelRow(conditionsCard, Str.get("nexus.disruption"), "0%");
        sovereignLabel = addLabelRow(conditionsCard, Str.get("nexus.sovereign"), "0");
        mainPanel.add(conditionsCard);

        render(BankSnapshot.get());
        return mainPanel;
    }

    /** Called by NexusUI's refresh thread: only reads the snapshot, then renders on the Swing thread. */
    @Override
    public void refresh() {
        final BankSnapshot s = BankSnapshot.get();
        if (s == null || s == shown) return;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { render(s); }
        });
    }

    /** Swing thread only. */
    private void render(BankSnapshot s) {
        if (s == null || mainPanel == null) return;
        shown = s;
        dateLabel.setText(Str.f("nexus.asOf", s.date));
        set(netWorthLabel, formatCredits(s.netWorth), s.netWorth >= 0 ? POSITIVE : NEGATIVE);
        set(creditsLabel, formatCredits(s.credits), TEXT_PRIMARY);
        set(debtLabel, formatCredits(s.debt), s.debt > 0 ? NEGATIVE : TEXT_PRIMARY);
        set(investLabel, formatCredits(s.invested), POSITIVE);
        set(dueLabel, formatCredits(s.dueNow), s.dueNow > 1 ? GOLD : TEXT_PRIMARY);
        set(pastDueLabel, formatCredits(s.late), s.late > 1 ? NEGATIVE : TEXT_PRIMARY);
        set(autopayLabel, Str.get(s.autopay ? "common.on" : "common.off"), s.autopay ? POSITIVE : NEGATIVE);
        payButton.setEnabled(s.dueNow + s.late > 1);
        autopayButton.setText(Str.get(s.autopay ? "nexus.autopayOff" : "nexus.autopayOn"));
        set(scoreLabel, s.scoreText, "--".equals(s.scoreText) ? TEXT_SECONDARY : getScoreColor(s.score));
        set(bracketLabel, s.bracket + ("NONE".equals(s.bankruptcyState) ? "" : " | " + Str.f("nexus.bankruptcy", s.bankruptcyLabel)),
            getScoreColor(s.score));
        fillList(loansPanel, s.loans, Str.get("terminal.loans.none"));
        fillList(investmentsPanel, s.investments, Str.get("terminal.invest.none"));
        set(warSurchargeLabel, String.format("+%.0f%%", s.warSurcharge * 100), s.warSurcharge > 0 ? NEGATIVE : TEXT_PRIMARY);
        set(disruptionLabel, String.format("%.1f%%", s.disruption * 100), s.disruption > 0 ? NEGATIVE : TEXT_PRIMARY);
        set(sovereignLabel, formatCredits(s.sovereignDebt), TEXT_PRIMARY);
    }

    // ------------------------------------------------------------------ actions (run on the game thread)

    private void sendPayAllDue() {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                BankData data = BankData.get();
                float paid = 0f;
                for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
                    float due = loan.amountPastDue;
                    if (due > 1f && data.getLoanManager().makePayment(loan.accountId, due)) paid += due;
                }
                BankSnapshot.capture();
                return "paid " + (int) paid;
            }
        });
    }

    private void sendToggleAutopay() {
        enqueue(new GameDataBridge.GameCommand() {
            public String execute() {
                BankData data = BankData.get();
                data.setAutopayEnabled(!data.isAutopayEnabled());
                BankSnapshot.capture();
                return "autopay " + data.isAutopayEnabled();
            }
        });
    }

    private static void enqueue(GameDataBridge.GameCommand command) {
        GameDataBridge bridge = GameDataBridge.getInstance();
        if (bridge != null) bridge.enqueueCommand(command);
    }

    // ------------------------------------------------------------------ UI helpers

    private void fillList(JPanel panel, List<BankSnapshot.Line> lines, String empty) {
        panel.removeAll();
        if (lines.isEmpty()) {
            JLabel none = new JLabel(empty);
            none.setForeground(TEXT_SECONDARY);
            none.setFont(new Font("SansSerif", Font.PLAIN, 11));
            panel.add(none);
        } else {
            for (BankSnapshot.Line l : lines) {
                JLabel row = new JLabel(l.label + " | " + l.detail);
                row.setForeground(l.bad ? NEGATIVE : POSITIVE);
                row.setFont(new Font("SansSerif", Font.PLAIN, 11));
                panel.add(row);
            }
        }
        panel.revalidate();
        panel.repaint();
    }

    private JPanel createCard(String title) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 80), 1),
            new EmptyBorder(8, 10, 8, 10)
        ));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        titleLabel.setForeground(GOLD);
        card.add(titleLabel);
        card.add(Box.createVerticalStrut(4));
        return card;
    }

    private JLabel addLabelRow(JPanel card, String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setBackground(CARD_BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

        JLabel keyLabel = new JLabel(label + " ");
        keyLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        keyLabel.setForeground(TEXT_SECONDARY);

        JLabel valueLabel = new JLabel(value);
        valueLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        valueLabel.setForeground(TEXT_PRIMARY);

        row.add(keyLabel);
        row.add(valueLabel);
        card.add(row);
        return valueLabel;
    }

    private static void set(JLabel label, String text, Color color) {
        if (label == null) return;
        label.setText(text);
        label.setForeground(color);
    }

    private static Color getScoreColor(int score) {
        if (score >= 750) return POSITIVE;
        if (score >= 650) return GOLD;
        if (score >= 500) return new Color(200, 180, 60);
        return NEGATIVE;
    }

    private static String formatCredits(float amount) {
        if (Math.abs(amount) >= 1000000) return String.format("%.1fM", amount / 1000000f);
        if (Math.abs(amount) >= 1000) return String.format("%.0fk", amount / 1000f);
        return String.format("%.0f", amount);
    }
}
