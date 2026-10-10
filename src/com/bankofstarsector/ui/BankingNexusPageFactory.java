package com.bankofstarsector.ui;

import com.nexusui.api.NexusPage;
import com.nexusui.api.NexusPageFactory;

public class BankingNexusPageFactory implements NexusPageFactory {

    @Override
    public String getId() { return "pbc_banking"; }

    @Override
    public String getTitle() { return com.bankofstarsector.core.Str.get("nexus.title"); }

    @Override
    public NexusPage create() { return new BankingNexusPage(); }
}
