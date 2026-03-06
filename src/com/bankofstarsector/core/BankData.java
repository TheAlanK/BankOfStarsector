package com.bankofstarsector.core;

import com.fs.starfarer.api.Global;
import com.bankofstarsector.banking.*;
import com.bankofstarsector.collection.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class BankData implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String PERSISTENT_KEY = "bos_data";

    private LoanManager loanManager;
    private InvestmentManager investmentManager;
    private CreditScoreManager creditScoreManager;
    private InterestEngine interestEngine;
    private CollectionManager collectionManager;
    private AssetSeizureManager assetSeizureManager;
    private BankruptcyManager bankruptcyManager;
    private List<TransactionRecord> transactionHistory;

    private BankData() {
        loanManager = new LoanManager();
        investmentManager = new InvestmentManager();
        creditScoreManager = new CreditScoreManager();
        interestEngine = new InterestEngine();
        collectionManager = new CollectionManager();
        assetSeizureManager = new AssetSeizureManager();
        bankruptcyManager = new BankruptcyManager();
        transactionHistory = new ArrayList<TransactionRecord>();
    }

    public static BankData get() {
        Object stored = Global.getSector().getPersistentData().get(PERSISTENT_KEY);
        if (stored instanceof BankData) {
            BankData data = (BankData) stored;
            data.ensureInitialized();
            return data;
        }
        BankData data = new BankData();
        Global.getSector().getPersistentData().put(PERSISTENT_KEY, data);
        return data;
    }

    private void ensureInitialized() {
        if (loanManager == null) loanManager = new LoanManager();
        if (investmentManager == null) investmentManager = new InvestmentManager();
        if (creditScoreManager == null) creditScoreManager = new CreditScoreManager();
        if (interestEngine == null) interestEngine = new InterestEngine();
        if (collectionManager == null) collectionManager = new CollectionManager();
        if (assetSeizureManager == null) assetSeizureManager = new AssetSeizureManager();
        if (bankruptcyManager == null) bankruptcyManager = new BankruptcyManager();
        if (transactionHistory == null) transactionHistory = new ArrayList<TransactionRecord>();
    }

    public LoanManager getLoanManager() { return loanManager; }
    public InvestmentManager getInvestmentManager() { return investmentManager; }
    public CreditScoreManager getCreditScoreManager() { return creditScoreManager; }
    public InterestEngine getInterestEngine() { return interestEngine; }
    public CollectionManager getCollectionManager() { return collectionManager; }
    public AssetSeizureManager getAssetSeizureManager() { return assetSeizureManager; }
    public BankruptcyManager getBankruptcyManager() { return bankruptcyManager; }

    public List<TransactionRecord> getTransactionHistory() { return transactionHistory; }

    public void addTransaction(String type, float amount, String description) {
        long timestamp = Global.getSector().getClock().getTimestamp();
        transactionHistory.add(0, new TransactionRecord(type, amount, description, timestamp));
        if (transactionHistory.size() > 50) {
            transactionHistory.remove(transactionHistory.size() - 1);
        }
    }

    public float getNetWorth() {
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        float investments = investmentManager.getTotalValue();
        float debt = loanManager.getTotalDebt();
        return credits + investments - debt;
    }

    public static class TransactionRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public String type;
        public float amount;
        public String description;
        public long timestamp;

        public TransactionRecord(String type, float amount, String description, long timestamp) {
            this.type = type;
            this.amount = amount;
            this.description = description;
            this.timestamp = timestamp;
        }
    }
}
