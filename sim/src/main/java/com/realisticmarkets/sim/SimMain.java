package com.realisticmarkets.sim;

import java.util.Arrays;

/**
 * Entry point for headless simulations.
 *
 * <pre>
 *   ./scripts/dev.sh sim farm [item] [days]        farm income vs daily sale size (Dealer balance)
 *   ./scripts/dev.sh sim auction [steps] [seed]    batch-auction price discovery demo
 * </pre>
 */
public final class SimMain {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "farm";
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (mode) {
            case "farm" -> FarmSim.main(rest);
            case "auction" -> AuctionSim.main(rest);
            default -> {
                System.err.println("unknown sim '" + mode + "'. Try: farm, auction");
                System.exit(2);
            }
        }
    }
}
