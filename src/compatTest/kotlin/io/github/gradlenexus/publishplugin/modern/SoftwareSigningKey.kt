/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gradlenexus.publishplugin.modern

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.util.Date

/** Fresh software-only test key; never installed in a provider registry, keyring or agent. */
internal class SoftwareSigningKey {
    private val provider = BouncyCastleProvider()
    val password = java.util.UUID.randomUUID().toString()
    private val pair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }.generateKeyPair(), Date())
    private val calculators = JcaPGPDigestCalculatorProviderBuilder().setProvider(provider).build()
    private val ring = PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        pair,
        "BundleFixture ephemeral software test",
        calculators.get(HashAlgorithmTags.SHA1),
        null,
        null,
        JcaPGPContentSignerBuilder(pair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(provider),
        JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, calculators.get(HashAlgorithmTags.SHA256)).setProvider(provider).build(password.toCharArray())
    )
    private val publicKeys = ring.generatePublicKeyRing()
    val armored: String = ByteArrayOutputStream().let { bytes ->
        ArmoredOutputStream(bytes).use { ring.generateSecretKeyRing().encode(it) }
        bytes.toString("UTF-8")
    }
    fun verifies(signatureBytes: ByteArray, payload: ByteArray): Boolean {
        val factory = PGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signatureBytes)), JcaKeyFingerprintCalculator())
        val signatures = factory.nextObject() as PGPSignatureList
        val signature = signatures.get(0)
        signature.init(JcaPGPContentVerifierBuilderProvider().setProvider(provider), publicKeys.getPublicKey(signature.keyID))
        signature.update(payload)
        return signature.verify()
    }
}
