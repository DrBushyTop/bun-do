package fi.bundo.household

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.bundo.data.AccountData
import fi.bundo.identity.SignInModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal class HouseholdModel(private val account: AccountData, private val signIn: SignInModel) : ViewModel() {
    var homes by mutableStateOf<List<Household>>(emptyList())
        private set
    var current by mutableStateOf<Household?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var failure by mutableStateOf("")
        private set
    var shareLink by mutableStateOf<String?>(null)
        private set
    private val endpoint = HouseholdEndpoint()
    private var createId = UUID.randomUUID().toString()

    fun refresh() = run {
        val response = send(JSONObject().put("action", "list"))
        val array = response.getJSONArray("households")
        homes = (0 until array.length()).map { Household.read(array.getJSONObject(it)) }
            .sortedWith(compareBy<Household> { it.deleted }.thenBy { !it.active }.thenBy { it.name })
        current = current?.let { old ->
            homes.find { it.id == old.id }.also { if (it == null && old.active) failure = "FORBIDDEN" }
        }
        account.selectedHousehold.value?.let { id ->
            if (homes.none { it.id == id && it.active }) account.selectHousehold(null)
        }
    }

    fun open(home: Household) { current = home; failure = ""; shareLink = null; refresh() }
    fun back() { current = null; shareLink = null; failure = "" }
    fun select(home: Household, onSelected: () -> Unit = {}) = run {
        if (home.active && !home.deleted) {
            account.selectHousehold(home.id, home.epoch, home.name)
            onSelected()
        }
    }
    fun clearShare() { shareLink = null }

    fun create(name: String, displayName: String, requestId: String = createId, onCreated: (Household) -> Unit = {}) = run {
        update(send(JSONObject().put("action", "create").put("workspaceId", requestId).put("name", name).put("displayName", displayName)))
        current?.let(onCreated)
        createId = UUID.randomUUID().toString()
    }

    fun restore(id: String) { current = homes.find { it.id == id }; shareLink = null }

    fun redeem(value: String, displayName: String, onRedeemed: (Household) -> Unit = {}) {
        val link = InvitationLink.parse(value)
        if (link == null) { failure = "INVALID_LINK"; return }
        run {
            update(send(JSONObject().put("action", "redeem").put("workspaceId", link.workspace)
                .put("stateEpoch", link.epoch).put("invitationId", link.invitation).put("secret", link.secret).put("displayName", displayName)))
            current?.let(onRedeemed)
        }
    }

    fun command(action: String, home: Household, invitation: HouseholdInvitation? = null,
        member: HouseholdMember? = null, code: String? = null) = run {
        val body = membershipRequest(action, home, invitation, member, code)
        val response = send(body)
        if (response.optJSONObject("household") == null) {
            homes = homes.filterNot { it.id == home.id }
            if (account.selectedHousehold.value == home.id) account.selectHousehold(null)
        }
        update(response)
    }

    private suspend fun send(body: JSONObject) = signIn.withAccountToken(account) { token, registration ->
        endpoint.send(token, registration, body)
    }

    private suspend fun update(response: JSONObject) {
        current = response.optJSONObject("household")?.let(Household::read)
        current?.let { home ->
            homes = homes.filterNot { it.id == home.id } + home
            if (!home.active && account.selectedHousehold.value == home.id) account.selectHousehold(null)
        }
        shareLink = response.optString("invitationLink").takeUnless { it.isEmpty() || it == "null" }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = ""
        shareLink = null
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                account.lease.check()
                failure = (error as? HouseholdFailure)?.code ?: "UNAVAILABLE"
                if (failure == "FORBIDDEN") {
                    current?.let { denied ->
                        homes = homes.filterNot { it.id == denied.id }
                        if (account.selectedHousehold.value == denied.id) account.selectHousehold(null)
                    }
                    current = null
                }
            }
            finally { busy = false }
        }
    }
}
