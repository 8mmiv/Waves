package com.wavesplatform.state.diffs.smart.eth

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.db.WithDomain
import com.wavesplatform.lang.directives.values.V6
import com.wavesplatform.lang.v1.compiler.TestCompiler
import com.wavesplatform.state.Portfolio
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.state.diffs.ci.ciFee
import com.wavesplatform.state.diffs.smart.predef.{assertProvenPart, provenPart}
import com.wavesplatform.test.{PropSpec, TestTime}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.IssueTransaction
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{ERC20Address, EthereumTransaction, GenesisTransaction}
import com.wavesplatform.utils.EthHelpers

class EthereumTransferSmartTest extends PropSpec with WithDomain with EthHelpers {
  import DomainPresets._

  private val time = new TestTime
  private def ts   = time.getTimestamp()

  private val transferAmount = 1234

  private def script(tx: EthereumTransaction, recipient: Address) = TestCompiler(V6).compileExpression(
    s"""
       | let t = transferTransactionById(base58'${tx.id()}').value()
       | ${checkEthTransfer(tx, "unit", recipient)}
     """.stripMargin
  )

  private def assetScript(tx: EthereumTransaction, recipient: Address) = TestCompiler(V6).compileAsset {
    s"""
       | match tx {
       |   case t: TransferTransaction =>
       |    if (t.version == 0)
       |      then {
       |        ${checkEthTransfer(tx, "this.id", recipient)}
       |      } else {
       |        t.amount == $ENOUGH_AMT
       |      }
       |
       |   case _ => true
       | }
     """.stripMargin
  }

  private def checkEthTransfer(tx: EthereumTransaction, asset: String, recipient: Address): String =
    s"""
       | ${provenPart(tx, emptyBodyBytes = true, checkProofs = false)}
       | let amount     = t.amount == $transferAmount
       | let feeAssetId = t.feeAssetId == unit
       | let assetId    = t.assetId == $asset
       | let attachment = t.attachment == base58'${ByteStr.empty}'
       | let recipient = match (t.recipient) {
       |   case a: Address => a.bytes == base58'$recipient'
       |   case a: Alias   => throw("unexpected")
       | }
       | ${assertProvenPart("t", proofs = false)} && amount && assetId && feeAssetId && recipient && attachment
     """.stripMargin

  property("transferTransactionById") {
    val fee         = ciFee().sample.get
    val recipient   = accountGen.sample.get
    val transfer    = EthereumTransaction.Transfer(None, transferAmount, recipient.toAddress)
    val ethTransfer = EthereumTransaction(transfer, TestEthUnderlying, TestEthSignature, 'T'.toByte)
    val gTx1        = GenesisTransaction.create(ethTransfer.senderAddress(), ENOUGH_AMT, ts).explicitGet()
    val gTx2        = GenesisTransaction.create(recipient.toAddress, ENOUGH_AMT, ts).explicitGet()
    val verifier    = Some(script(ethTransfer, recipient.toAddress))
    val setVerifier = () => SetScriptTransaction.selfSigned(1.toByte, recipient, verifier, fee, ts).explicitGet()

    withDomain(RideV6) { d =>
      d.appendBlock(gTx1, gTx2, setVerifier())
      d.appendBlock(ethTransfer)

      d.liquidDiff.portfolios(recipient.toAddress) shouldBe Portfolio.waves(transferAmount)
      d.liquidDiff.portfolios(ethTransfer.senderAddress()) shouldBe Portfolio.waves(-ethTransfer.underlying.getGasPrice.longValue() - transferAmount)

      d.appendBlock()
      d.appendBlock(setVerifier())
      d.liquidDiff.scriptsRun shouldBe 1
    }
  }

  property("scripted asset") {
    val fee       = ciFee().sample.get
    val recipient = accountGen.sample.get

    val dummyTransfer    = EthereumTransaction.Transfer(None, transferAmount, recipient.toAddress)
    val dummyEthTransfer = EthereumTransaction(dummyTransfer, TestEthUnderlying, TestEthSignature, 'T'.toByte) // needed to pass into asset script

    val sender      = dummyEthTransfer.senderAddress()
    val aScript     = assetScript(dummyEthTransfer, recipient.toAddress)
    val issue       = IssueTransaction.selfSigned(2.toByte, recipient, "Asset", "", ENOUGH_AMT, 8, true, Some(aScript), fee, ts).explicitGet()
    val asset       = IssuedAsset(issue.id())
    val preTransfer = TransferTransaction.selfSigned(2.toByte, recipient, sender, asset, ENOUGH_AMT, Waves, fee, ByteStr.empty, ts).explicitGet()

    val ethTransfer = dummyEthTransfer.copy(dummyTransfer.copy(Some(ERC20Address(asset.id.take(20)))))

    val gTx1 = GenesisTransaction.create(sender, ENOUGH_AMT, ts).explicitGet()
    val gTx2 = GenesisTransaction.create(recipient.toAddress, ENOUGH_AMT, ts).explicitGet()

    withDomain(RideV6) { d =>
      d.appendBlock(gTx1, gTx2, issue, preTransfer)
      d.appendBlock(ethTransfer)

      d.liquidDiff.errorMessage(ethTransfer.id()) shouldBe None
      d.liquidDiff.portfolios(recipient.toAddress) shouldBe Portfolio.build(asset, transferAmount)
      d.liquidDiff.portfolios(ethTransfer.senderAddress()) shouldBe Portfolio(-ethTransfer.underlying.getGasPrice.longValue(),
                                                                              assets = Map(asset -> -transferAmount))

      d.liquidDiff.scriptsComplexity should be > 0L
    }
  }
}