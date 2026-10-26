package com.turing.thumbcard.ui.auth

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.turing.thumbcard.R
import com.turing.thumbcard.databinding.ActivityLoginBinding

/**
 * 登录 / 注册页。
 *
 * ⚠️ 状态：**功能开发中**。
 * 服务端（账号体系、微信/支付宝 OAuth、短信验证码）尚未就绪，
 * 因此三个渠道均只做「开发中」提示，**不生成假账号、不写本地登录态**，
 * 避免用户误以为注册成功、后续接入时产生脏数据。
 *
 * 接入顺序建议：
 *   1. 手机号验证码（最快，无需三方资质）
 *   2. 微信 OpenSDK（需开放平台应用审核）
 *   3. 支付宝（需签约）
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnWechat.setOnClickListener { notReady(getString(R.string.login_channel_wechat)) }
        binding.btnAlipay.setOnClickListener { notReady(getString(R.string.login_channel_alipay)) }
        binding.btnPhone.setOnClickListener { notReady(getString(R.string.login_channel_phone)) }

        binding.tvAgreement.setOnClickListener {
            Toast.makeText(this, getString(R.string.login_privacy_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun notReady(channel: String) {
        Toast.makeText(
            this,
            getString(R.string.login_not_ready_fmt, channel),
            Toast.LENGTH_SHORT
        ).show()
    }
}
