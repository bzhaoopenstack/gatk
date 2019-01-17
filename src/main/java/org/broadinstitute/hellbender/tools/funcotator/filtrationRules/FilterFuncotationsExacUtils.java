package org.broadinstitute.hellbender.tools.funcotator.filtrationRules;

import org.broadinstitute.hellbender.utils.param.ParamUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FilterFuncotationsExacUtils {
    /**
     * Sub-population suffixes used within ExAC. Used for calculating max MAF.
     */
    private enum ExacSubPopulation {
        AFR, AMR, EAS, FIN, NFE, OTH, SAS
    }

    /**
     * Prefix for allele-count Funcotations for each ExAC sub-population.
     */
    private static String EXAC_ALLELE_COUNT_PREFIX = "ExAC_AC_";

    /**
     * Prefix for allele-number Funcotations for each ExAC sub-population.
     */
    private static String EXAC_ALLELE_NUMBER_PREFIX = "ExAC_AN_";

    /**
     * Build a {@link FuncotationFiltrationRule} matching Funcotations from variants with a
     * maximum MAF less than some threshold.
     *
     * @param maxMaf the MAF threshold to check in the rule. Must be in the range [0, 1]
     * @return a {@link FuncotationFiltrationRule} matching Funcotations with a MAF (AC/AN)
     *         less than {@code maxMaf} across all sub-populations of ExAC
     */
    public static FuncotationFiltrationRule buildExacMaxMafRule(final double maxMaf) {
        ParamUtils.inRange(maxMaf, 0, 1, "MAF must be between 0 and 1");
        return funcotations -> getMaxMinorAlleleFreq(funcotations) <= maxMaf;
    }

    /**
     * Calculate the max MAF across all ExAC sub-populations from the given Funcotations.
     *
     * If a sub-population has an allele number of zero, it will be assigned a MAF of zero.
     */
    private static double getMaxMinorAlleleFreq(final Set<Map.Entry<String, String>> funcotations) {
        return Arrays.stream(ExacSubPopulation.values())
                .filter(subpop -> funcotations.stream().anyMatch(entry -> entry.getKey().equals(EXAC_ALLELE_COUNT_PREFIX + subpop.name())))
                .map(subpop -> {
                    final String exacAlleleCount = EXAC_ALLELE_COUNT_PREFIX + subpop.name();
                    final String exacAlleleNumber = EXAC_ALLELE_NUMBER_PREFIX + subpop.name();
                    final List<String> keys = Arrays.asList(exacAlleleCount, exacAlleleNumber);
                    final Stream<Map.Entry<String, String>> matchingFuncotations = funcotations.stream().filter(entry -> keys.contains(entry.getKey()));
                    final Double ac = matchingFuncotations
                            .filter(entry1 -> entry1.getKey().equals(exacAlleleCount))
                            .findFirst()
                            .map(entry -> Double.valueOf(entry.getValue()))
                            .orElse(0d);
                    final Integer an = matchingFuncotations
                            .filter(entry1 -> entry1.getKey().equals(exacAlleleNumber))
                            .findFirst()
                            .map(entry -> Integer.valueOf(entry.getValue()))
                            .orElse(0);

                    if (an == 0) {
                        // If a variant has never been seen in ExAC, report it as 0% MAF.
                        return 0d;
                    } else {
                        return ac / an;
                    }
                })
                .max(Double::compareTo)
                .orElse(0d);
    }
}
