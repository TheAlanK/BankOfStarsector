package com.bankofstarsector.ui;

import com.nexusui.api.NexusPage;
import com.bankofstarsector.banking.*;
import com.bankofstarsector.collection.BankruptcyManager;
import com.bankofstarsector.core.BankData;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class BankingNexusPage implements NexusPage {

    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color DARK_NAVY = new Color(20, 30, 60);
    private static final Color CARD_BG = new Color(30, 35, 50);
    private static final Color TEXT_PRIMARY = new Color(220, 220, 220);
    private static final Color TEXT_SECONDARY = new Color(160, 160, 160);
    private static final Color POSITIVE = new Color(100, 200, 100);
    private static final Color NEGATIVE = new Color(220, 80, 80);

    private JPanel mainPanel;
    private JLabel netWorthLabel;
    private JLabel creditsLabel;
    private JLabel debtLabel;
    private JLabel investLabel;
    private JLabel scoreLabel;
    private JLabel bracketLabel;
    private JPanel loansPanel;
    private JPanel investmentsPanel;
    private JLabel warSurchargeLabel;
    private JLabel disruptionLabel;
    private int port;

    @Override
    public String getId() { return "pbc_banking"; }

    @Override
    public String getTitle() { return "PBC Banking"; }

    @Override
    public JPanel createPanel(int port) {
        this.port = port;

        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(DARK_NAVY);
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Header
        JLabel header = new JLabel("PERSEAN BANKING CONFEDERATION");
        header.setFont(new Font("SansSerif", Font.BOLD, 16));
        header.setForeground(GOLD);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(header);
        mainPanel.add(Box.createVerticalStrut(10));

        // Account overview card
        JPanel overviewCard = createCard("Account Overview");
        netWorthLabel = addLabelRow(overviewCard, "Net Worth:", "--");
        creditsLabel = addLabelRow(overviewCard, "Credits:", "--");
        debtLabel = addLabelRow(overviewCard, "Total Debt:", "--");
        investLabel = addLabelRow(overviewCard, "Investments:", "--");
        mainPanel.add(overviewCard);
        mainPanel.add(Box.createVerticalStrut(8));

        // Credit score card
        JPanel scoreCard = createCard("Credit Score");
        scoreLabel = addLabelRow(scoreCard, "Score:", "--");
        bracketLabel = addLabelRow(scoreCard, "Bracket:", "--");
        mainPanel.add(scoreCard);
        mainPanel.add(Box.createVerticalStrut(8));

        // Active loans card
        JPanel loansCard = createCard("Active Loans");
        loansPanel = new JPanel();
        loansPanel.setLayout(new BoxLayout(loansPanel, BoxLayout.Y_AXIS));
        loansPanel.setBackground(CARD_BG);
        loansCard.add(loansPanel);
        mainPanel.add(loansCard);
        mainPanel.add(Box.createVerticalStrut(8));

        // Active investments card
        JPanel investCard = createCard("Investments");
        investmentsPanel = new JPanel();
        investmentsPanel.setLayout(new BoxLayout(investmentsPanel, BoxLayout.Y_AXIS));
        investmentsPanel.setBackground(CARD_BG);
        investCard.add(investmentsPanel);
        mainPanel.add(investCard);
        mainPanel.add(Box.createVerticalStrut(8));

        // Sector conditions card
        JPanel conditionsCard = createCard("Sector Conditions");
        warSurchargeLabel = addLabelRow(conditionsCard, "War Surcharge:", "0%");
        disruptionLabel = addLabelRow(conditionsCard, "Market Disruption:", "0%");
        mainPanel.add(conditionsCard);

        // Initial data load
        refresh();

        return mainPanel;
    }

    @Override
    public void refresh() {
        try {
            BankData data = BankData.get();
            if (data == null) return;

            CreditScoreManager csm = data.getCreditScoreManager();
            LoanManager lm = data.getLoanManager();
            InvestmentManager im = data.getInvestmentManager();
            InterestEngine engine = data.getInterestEngine();

            // Update overview
            float netWorth = data.getNetWorth();
            updateLabel(netWorthLabel, formatCredits(netWorth), netWorth >= 0 ? POSITIVE : NEGATIVE);
            updateLabel(creditsLabel, formatCredits(
                com.fs.starfarer.api.Global.getSector().getPlayerFleet().getCargo().getCredits().get()),
                TEXT_PRIMARY);
            float debt = lm.getTotalDebt();
            updateLabel(debtLabel, formatCredits(debt), debt > 0 ? NEGATIVE : TEXT_PRIMARY);
            updateLabel(investLabel, formatCredits(im.getTotalValue()), POSITIVE);

            // Update credit score
            updateLabel(scoreLabel, "" + csm.getScore(), getScoreColor(csm.getScore()));
            updateLabel(bracketLabel, csm.getBracket(), getScoreColor(csm.getScore()));

            // Update loans
            updateLoansPanel(lm.getActiveLoans());

            // Update investments
            updateInvestmentsPanel(im.getActiveInvestments());

            // Update conditions
            float warSurcharge = engine.getWarSurcharge();
            float disruption = engine.getMarketDisruptionModifier();
            updateLabel(warSurchargeLabel,
                String.format("+%.0f%%", warSurcharge * 100),
                warSurcharge > 0 ? NEGATIVE : TEXT_PRIMARY);
            updateLabel(disruptionLabel,
                String.format("-%.0f%%", disruption * 100),
                disruption > 0 ? NEGATIVE : TEXT_PRIMARY);

        } catch (Exception e) {
            // Silently handle - data may not be available yet
        }
    }

    private void updateLoansPanel(List<BankAccount> loans) {
        if (loansPanel == null) return;
        loansPanel.removeAll();
        if (loans.isEmpty()) {
            JLabel none = new JLabel("No active loans");
            none.setForeground(TEXT_SECONDARY);
            none.setFont(new Font("SansSerif", Font.PLAIN, 11));
            loansPanel.add(none);
        } else {
            for (BankAccount loan : loans) {
                JLabel loanLabel = new JLabel(String.format("%s | %s | %s",
                    loan.loanType.displayName,
                    formatCredits(loan.remainingBalance),
                    loan.getStatusDisplay()));
                Color c = loan.status == LoanStatus.ACTIVE ? POSITIVE : NEGATIVE;
                loanLabel.setForeground(c);
                loanLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
                loansPanel.add(loanLabel);
            }
        }
        loansPanel.revalidate();
        loansPanel.repaint();
    }

    private void updateInvestmentsPanel(List<BankAccount> investments) {
        if (investmentsPanel == null) return;
        investmentsPanel.removeAll();
        if (investments.isEmpty()) {
            JLabel none = new JLabel("No active investments");
            none.setForeground(TEXT_SECONDARY);
            none.setFont(new Font("SansSerif", Font.PLAIN, 11));
            investmentsPanel.add(none);
        } else {
            for (BankAccount inv : investments) {
                float returnPct = inv.investedAmount > 0 ?
                    ((inv.currentValue - inv.investedAmount) / inv.investedAmount) * 100 : 0;
                JLabel invLabel = new JLabel(String.format("%s | %s | %+.1f%%",
                    inv.investmentType.displayName,
                    formatCredits(inv.currentValue),
                    returnPct));
                invLabel.setForeground(returnPct >= 0 ? POSITIVE : NEGATIVE);
                invLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
                investmentsPanel.add(invLabel);
            }
        }
        investmentsPanel.revalidate();
        investmentsPanel.repaint();
    }

    // ========== UI HELPERS ==========

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

    private void updateLabel(JLabel label, String text, Color color) {
        if (label != null) {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    label.setText(text);
                    label.setForeground(color);
                }
            });
        }
    }

    private Color getScoreColor(int score) {
        if (score >= 750) return POSITIVE;
        if (score >= 650) return GOLD;
        if (score >= 500) return new Color(200, 180, 60);
        return NEGATIVE;
    }

    private String formatCredits(float amount) {
        if (amount >= 1000000) return String.format("%.1fM", amount / 1000000f);
        if (amount >= 1000) return String.format("%.0fk", amount / 1000f);
        return String.format("%.0f", amount);
    }
}
