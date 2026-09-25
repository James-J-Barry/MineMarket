package com.realisticmarkets.sim;

import java.util.Arrays;

/**
 * Entry point for headless simulations.
 *
 * <pre>
 *   ./scripts/dev.sh sim farm [item] [days]        farm income vs daily sale size (Dealer balance)
 *   ./scripts/dev.sh sim auction [steps] [seed]    batch-auction price discovery demo
 *   ./scripts/dev.sh sim progression [profile]     Tier 1 play time, then Trade Route Crate income uplift
 *   ./scripts/dev.sh sim floor                     Trading Floor liquidity, spreads, impact and no-arbitrage
 *   ./scripts/dev.sh sim equities                  six companies' earnings, share prices and returns vs the vault
 *   ./scripts/dev.sh sim trader                    does the Floor make a player money? gatherer uplift, trading strategies
 *   ./scripts/dev.sh sim hedge                     a wheat farmer unhedged vs forwards vs futures; a crash; a speculator
 * </pre>
 */
public final class SimMain {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "farm";
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (mode) {
            case "farm" -> FarmSim.main(rest);
            case "auction" -> AuctionSim.main(rest);
            case "progression" -> ProgressionSim.main(rest);
            case "floor" -> FloorSim.main(rest);
            case "trader" -> TraderSim.main(rest);
            case "equities" -> EquitySim.main(rest);
            case "hedge" -> HedgeSim.main(rest);
            default -> {
                System.err.println("unknown sim '" + mode + "'. Try: farm, auction, progression, floor, trader, equities, hedge");
                System.exit(2);
            }
        }
    }
}
