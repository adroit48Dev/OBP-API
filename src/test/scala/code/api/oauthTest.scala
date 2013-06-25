package code.api

import org.scalatest._
import dispatch._
import oauth._
import OAuth._
import net.liftweb.util.Helpers._
import net.liftweb.http.S
import net.liftweb.common.Box
import code.api.test.{ServerSetup, APIResponse}
import code.model.dataAccess.OBPUser
import code.model.{Consumer => OBPConsumer, Token => OBPToken}
import code.model.TokenType._
import org.scalatest.selenium._
import org.openqa.selenium.WebDriver


case class OAuhtResponse(
  code: Int,
  body: String
)

class OAuthTest extends ServerSetup{

  val oauthRequest = baseRequest / "oauth"

  lazy val testConsumer =
    OBPConsumer.create.
      name("test application").
      isActive(true).
      key(randomString(40).toLowerCase).
      secret(randomString(40).toLowerCase).
      saveMe

  lazy val user1Password = randomString(10)
  lazy val user1 =
    OBPUser.create.
      email(randomString(3)+"@example.com").
      password(user1Password).
      validated(true).
      firstName(randomString(10)).
      lastName(randomString(10)).
      saveMe

  lazy val consumer = new Consumer (testConsumer.key,testConsumer.secret)
  lazy val notRegisteredConsumer = new Consumer (randomString(5),randomString(5))

  def sendPostRequest(req: Request): h.HttpPackage[OAuhtResponse] = {
    val postReq = req.POST
    val responceHandler = postReq >- {s => s}
    h x responceHandler{
       case (code, _, _, body) => OAuhtResponse(code, body())
    }
  }

  def getRequestToken(consumer: Consumer, callback: String): h.HttpPackage[OAuhtResponse] = {
    val request = (oauthRequest / "initiate").POST <@ (consumer, callback)
    sendPostRequest(request)
  }

  def getAccessToken(consumer: Consumer, requestToken : Token, verifier : String): h.HttpPackage[OAuhtResponse] = {
    val request = (oauthRequest / "token").POST <@ (consumer, requestToken, verifier)
    sendPostRequest(request)
  }

  def extractToken(body: String) : Token = {
    val oauthParams = body.split("&")
    val token = oauthParams(0).split("=")(1)
    val secret = oauthParams(1).split("=")(1)
    Token(token, secret)
  }

  case class Browser() extends HtmlUnit{
    implicit val driver = webDriver
    def getVerifier(loginPage: String, userName: String, password: String) : Box[String] = {
      tryo{
        go.to(loginPage)
        textField("username").value = userName
        val pwField = NameQuery("password").webElement
        pwField.clear()
        pwField.sendKeys(password)
        click on XPathQuery("""//input[@type='submit']""")
        val newURL = currentUrl
        val verifier =
          if(newURL.contains("verifier"))
          {
            //we got redirected
            val params = newURL.split("&")
            params(1).split("=")(1)
          }
          else{
            //the verifier is in the page
            XPathQuery("""//div[@id='verifier']""").element.text
          }
        close()
        quit()
        verifier
      }
    }
  }

  def getVerifier(requestToken: String, userName: String, password: String): Box[String] = {
    val b = Browser()
    val loginPage = (oauthRequest / "authorize" <<? List(("oauth_token", requestToken))).to_uri.toString
    b.getVerifier(loginPage, userName, password)
  }

  def getVerifier(userName: String, password: String): Box[String] = {
    val b = Browser()
    val loginPage = (oauthRequest / "authorize").to_uri.toString
    b.getVerifier(loginPage, userName, password)
  }
  /************************ the tags ************************/

  object Oauth extends Tag("oauth")
  object RequestToken extends Tag("requestToken")
  object AccessToken extends Tag("accessToken")
  object Verifier extends Tag("verifier")


  /************************ the tests ************************/
  feature("request token"){
    scenario("we get a request token", RequestToken, Oauth) {
      Given("The application is registered and does not have a callback URL")
      When("the request is sent")
      val reply = getRequestToken(consumer, OAuth.oob)
      Then("we should get a 200 created code")
      reply.code should equal (200)
      And("we can extract the token")
      val requestToken = extractToken(reply.body)
    }
    scenario("we get a request token with a callback URL", RequestToken, Oauth) {
      Given("The application is registered and have a callback URL")
      When("the request is sent")
      val reply = getRequestToken(consumer, "localhost:8080/app")
      Then("we should get a 200 created code")
      reply.code should equal (200)
      And("we can extract the token")
      val requestToken = extractToken(reply.body)
    }
    scenario("we don't get a request token since the application is not registered", RequestToken, Oauth) {
      Given("The application not registered")
      When("the request is sent")
      val reply = getRequestToken(notRegisteredConsumer, OAuth.oob)
      Then("we should get a 401 created code")
      reply.code should equal (401)
    }
    scenario("we don't get a request token since the application is not registered even with a callback URL", RequestToken, Oauth) {
      Given("The application not registered")
      When("the request is sent")
      val reply = getRequestToken(notRegisteredConsumer, "localhost:8080/app")
      Then("we should get a 401 created code")
      reply.code should equal (401)
    }
  }
  feature("Verifier"){
    scenario("user login and get redirected to the application back", Verifier, Oauth){
      Given("we will use a valid request token")
      val reply = getRequestToken(consumer, "http://localhost:8000")
      val requestToken = extractToken(reply.body)
      When("the browser is launched to login")
      val verifier = getVerifier(requestToken.value, user1.email.get, user1Password)
      Then("we should get a verifier")
      verifier.get.nonEmpty should equal (true)
    }
    scenario("user login and is asked to enter the verifier manually", Verifier, Oauth){
      Given("we will use a valid request token")
      val reply = getRequestToken(consumer, OAuth.oob)
      val requestToken = extractToken(reply.body)
      When("the browser is launched to login")
      val verifier = getVerifier(requestToken.value, user1.email.get, user1Password)
      Then("we should get a verifier")
      verifier.nonEmpty should equal (true)
    }
    scenario("user cannot login because there is no token", Verifier, Oauth){
      Given("we will use a valid request token")
      When("the browser is launched to login")
      val verifier = getVerifier(user1.email.get, user1Password)
      Then("we should get a verifier")
      verifier.isEmpty should equal (true)
    }
    scenario("user cannot login because then token does not exist", Verifier, Oauth){
      Given("we will use a valid request token")
      When("the browser is launched to login")
      val verifier = getVerifier(randomString(4), user1.email.get, user1Password)
      Then("we should get a verifier")
      verifier.isEmpty should equal (true)
    }
  }
  feature("access token"){
    scenario("we get an access token without a callback", AccessToken, Oauth){
      Given("we will first get a request token and a verifier")
      val reply = getRequestToken(consumer, OAuth.oob)
      val requestToken = extractToken(reply.body)
      val verifier = getVerifier(requestToken.value, user1.email.get, user1Password)
      When("when we ask for an access token")
      val accessToken = getAccessToken(consumer, requestToken, verifier.get)
      Then("we should get an access token")
      extractToken(accessToken.body)
    }
    scenario("we get an access token with a callback", AccessToken, Oauth){
      Given("we will first get a request token and a verifier")
      val reply = getRequestToken(consumer, "http://localhost:8000")
      val requestToken = extractToken(reply.body)
      val verifier = getVerifier(requestToken.value, user1.email.get, user1Password)
      When("when we ask for an access token")
      val accessToken = getAccessToken(consumer, requestToken, verifier.get)
      Then("we should get an access token")
      extractToken(accessToken.body)
    }
    scenario("we don't get an access token because the verifier is wrong", AccessToken, Oauth){
      Given("we will first get a request token and a verifier")
      val reply = getRequestToken(consumer, OAuth.oob)
      val requestToken = extractToken(reply.body)
      When("when we ask for an access token")
      val accessTokenReply = getAccessToken(consumer, requestToken, randomString(5))
      Then("we should get a 401")
      accessTokenReply.code should equal (401)
    }
  }
}
