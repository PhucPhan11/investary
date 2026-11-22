package com.investary.knowledge.assistant;

import java.util.regex.Pattern;

import com.investary.research.link.Keywords;

/**
 * Detects trading instructions in model-written text, so generated reports never tell the user to buy or sell.
 * A brokerage rating quoted from a source ("Chờ mua") is not an instruction; phrases such as "nên mua" are.
 * Shared by daily briefs and notebook notes.
 */
public final class TradingLanguage {

    /** Accent-folded instruction phrases. */
    private static final Pattern TRADING = Pattern.compile(
            "\\b(mua vao|mua them|ban ra|ban bot|ban het|chot loi|cat lo|dat lenh|giai ngan|tang ty trong|giam ty trong|"
                    + "nen mua|nen ban|buy now|sell now|take profit|stop loss|place an order)\\b");

    private TradingLanguage() {
    }

    public static boolean isInstruction(String text) {
        return text != null && TRADING.matcher(Keywords.normalize(text)).find();
    }
}
