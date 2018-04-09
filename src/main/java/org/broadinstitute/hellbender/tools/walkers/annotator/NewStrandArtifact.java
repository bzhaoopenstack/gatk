package org.broadinstitute.hellbender.tools.walkers.annotator;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFormatHeaderLine;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.walkers.validation.basicshortmutpileup.BetaBinomialDistribution;
import org.broadinstitute.hellbender.utils.GATKProtectedVariantContextUtils;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.ReadLikelihoods;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.broadinstitute.hellbender.tools.walkers.annotator.NewStrandArtifact.ReadStrand.FWD;
import static org.broadinstitute.hellbender.tools.walkers.annotator.NewStrandArtifact.ReadStrand.REV;

/**
 * Created by tsato on 4/6/18.
 */
public class NewStrandArtifact extends GenotypeAnnotation implements StandardMutectAnnotation {
    // TODO: learn the prior. A fixed prior merely shifts the threshold and thus is not helpful
    final double artifactPrior = 1; // 1e-3;
    final double realVariantPrior = 1; // 1 - 2*artifactPrior;
    public static final double LOD_THRESHOLD = 0.9;

    public static final String STRAND_ARTIFACT_DIRECTION_KEY = "SA";
    public static final String STRAND_ARTIFACT_LOG10_ODDS_KEY = "SA_LOD";

    @Override
    public List<String> getKeyNames() {
        return Arrays.asList(STRAND_ARTIFACT_DIRECTION_KEY, STRAND_ARTIFACT_LOG10_ODDS_KEY);
    }

    @Override
    public void annotate(ReferenceContext ref, VariantContext vc, Genotype g, GenotypeBuilder gb, ReadLikelihoods<Allele> likelihoods) {
        Utils.nonNull(gb);
        Utils.nonNull(vc);
        Utils.nonNull(likelihoods);

        // Get the tumor lods to find the alt allele with highest LOD score
        final double[] tumorLods = GATKProtectedVariantContextUtils.getAttributeAsDoubleArray(vc, GATKVCFConstants.TUMOR_LOD_KEY, () -> null, -1);

        if (tumorLods==null) {
            // Skip a variant that's missing tumor lod
            return;
        }

        final int indexOfMaxTumorLod = MathUtils.maxElementIndex(tumorLods);
        final Allele altAlelle = vc.getAlternateAllele(indexOfMaxTumorLod);
        final Allele refAllele = vc.getReference();

        final Collection<ReadLikelihoods<Allele>.BestAllele> bestAlleles = likelihoods.bestAlleles(g.getSampleName());
        final int numFwdAltReads = (int) bestAlleles.stream()
                .filter(ba -> ba.isInformative() && ba.allele.equals(altAlelle) && !ba.read.isReverseStrand())
                .count();
        final int numRevAltReads = (int) bestAlleles.stream()
                .filter(ba -> ba.isInformative() && ba.allele.equals(altAlelle) && ba.read.isReverseStrand())
                .count();
        final int numFwdRefReads = (int) bestAlleles.stream()
                .filter(ba -> ba.isInformative() && ba.allele.equals(refAllele) && !ba.read.isReverseStrand())
                .count();
        final int numRevRefReads = (int) bestAlleles.stream()
                .filter(ba -> ba.isInformative() && ba.allele.equals(refAllele) && ba.read.isReverseStrand())
                .count();

        final int numAltReads = numFwdAltReads + numRevAltReads;
        final int numRefReads = numFwdRefReads + numRevRefReads;
        final int balancedPseudocount = 5;

        // For the null model, we use the forward and reverse strand count as the pseudocounts to the beta-binomial distribution,
        // where the beta encodes the distribution over the probability of drawing a forward alt read.
        // If we don't have enough ref reads, use a distribution with good density around p = 0.5
        final BetaBinomialDistribution betaBinomForNull = numRefReads < 5 ?
                new BetaBinomialDistribution(null, balancedPseudocount, balancedPseudocount, numAltReads) :
                new BetaBinomialDistribution(null,
                        numFwdRefReads == 0 ? 1 : numFwdRefReads, // alpha must be > 0
                        numRevRefReads == 0 ? 1 : numRevRefReads, // beta > 0
                        numAltReads);

        final double likelihoodOfRealVariant = betaBinomForNull.probability(numFwdAltReads);

        // For the alt model, pick reasonable pseudocounts for the betabinomial, keeping in mind that if the site is an artifact,
        // it's highly unlikely to see the other strand. So we'll encode that as our prior belief.
        final int alpha = 99;
        final int beta = 1;

        final BetaBinomialDistribution betaBinomForArtifact = new BetaBinomialDistribution(null, alpha, beta, numAltReads);
        final double likelihoodOfFwdArtifact = betaBinomForArtifact.probability(numFwdAltReads);
        final double likelihoodOfRevArtifact = betaBinomForArtifact.probability(numRevAltReads);

        final double normalizingConstant = likelihoodOfFwdArtifact + likelihoodOfRevArtifact + likelihoodOfRealVariant;
        final double posteriorOfRealVariant = (1/normalizingConstant) * likelihoodOfRealVariant;
        final double posteriorOfFwdArtifact = (1/normalizingConstant) * likelihoodOfFwdArtifact;
        final double posteriorOfRevArtifact = (1/normalizingConstant) * likelihoodOfRevArtifact;
        Utils.validate(Math.abs(posteriorOfFwdArtifact + posteriorOfRevArtifact + posteriorOfRealVariant - 1) < 1e-5,
                "Posterior probabilities must be normalized");

        if (posteriorOfRealVariant > Math.max(posteriorOfFwdArtifact, posteriorOfRevArtifact)){
            // This is not an artifact, no need to add annotations
            return;
        }

//        // Take the posterior log odds
//        final double posteriorLog10OddsFwd = Math.log10(posteriorOfFwdArtifact/posteriorOfRealVariant);
//        final double posteriorLog10OddsRev = Math.log10(posteriorOfRevArtifact/posteriorOfRealVariant);

        final ReadStrand artifactStrand = posteriorOfFwdArtifact > posteriorOfRevArtifact ? FWD : REV;
        // final double artifactLogOdds = artifactStrand == FWD ? posteriorLog10OddsFwd : posteriorLog10OddsRev;
        final double artifactProbability = artifactStrand == FWD ? posteriorOfFwdArtifact : posteriorOfRevArtifact;

        gb.attribute(STRAND_ARTIFACT_LOG10_ODDS_KEY, artifactProbability);
        if (artifactProbability > LOD_THRESHOLD) {
            gb.attribute(STRAND_ARTIFACT_DIRECTION_KEY, artifactStrand == FWD ? FWD : REV);
        }
    }

    @Override
    public List<VCFFormatHeaderLine> getDescriptions() {
        return null;
    }

    public enum ReadStrand {
        FWD, REV
    }
}