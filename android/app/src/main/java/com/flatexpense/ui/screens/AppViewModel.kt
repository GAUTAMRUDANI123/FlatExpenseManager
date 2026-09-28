package com.flatexpense.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flatexpense.data.Repository
import com.flatexpense.data.Session
import com.flatexpense.data.api.BulkExpectedRequest
import com.flatexpense.data.api.CategoryDto
import com.flatexpense.data.api.ActivityResponse
import com.flatexpense.data.api.ChangePasswordRequest
import com.flatexpense.data.api.CloseMonthRequest
import com.flatexpense.data.api.MonthsResponse
import com.flatexpense.data.api.ReopenMonthRequest
import com.flatexpense.data.api.SettlementResponse
import com.flatexpense.data.api.TrendResponse
import com.flatexpense.ui.common.formatMonth
import com.flatexpense.data.api.ContributionsResponse
import com.flatexpense.data.api.CreateCategoryRequest
import com.flatexpense.data.api.CreateExpenseRequest
import com.flatexpense.data.api.CreateMemberRequest
import com.flatexpense.data.api.DashboardResponse
import com.flatexpense.data.api.DecisionRequest
import com.flatexpense.data.api.ExpenseDetailResponse
import com.flatexpense.data.api.ExpenseDto
import com.flatexpense.data.api.FlatSummaryDto
import com.flatexpense.data.api.JoinRequestDto
import com.flatexpense.data.api.PendingJoinDto
import com.flatexpense.data.api.LoginRequest
import com.flatexpense.data.api.MemberDto
import com.flatexpense.data.api.MonthlyReportResponse
import com.flatexpense.data.api.RecordContributionRequest
import com.flatexpense.data.api.RegisterRequest
import com.flatexpense.data.api.TransferAdminRequest
import com.flatexpense.data.api.UpdateCategoryRequest
import com.flatexpense.data.api.UpdateExpenseRequest
import com.flatexpense.data.api.UpdateMemberRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null
)

/** One reusable shape for every "load a thing from the API" screen. */
data class Async<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null
)

/** Long enough that a typed word is one request, short enough to feel live. */
private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Everything narrowing the expense list besides the status chips, which
 * predate this and stay where they are.
 *
 * Held as one object so the list has a single source of truth: with each
 * filter owning its own state the screen could easily send four of them and
 * forget the fifth.
 */
data class ExpenseFilters(
    val paidBy: Long? = null,
    val categoryId: Long? = null,
    val minAmount: String? = null,
    val maxAmount: String? = null,
    val from: String? = null,
    val to: String? = null
) {
    val active: Int
        get() = listOf(paidBy, categoryId, minAmount, maxAmount, from, to).count { it != null }

    val hasDateRange: Boolean get() = from != null || to != null
}

data class ToastMessage(val text: String, val id: Long = System.nanoTime())

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)

    val session: StateFlow<Session> = repo.sessionStore.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, Session())

    private val _auth = MutableStateFlow(AuthUiState())
    val auth: StateFlow<AuthUiState> = _auth.asStateFlow()

    private val _month = MutableStateFlow(currentMonth())
    val month: StateFlow<String> = _month.asStateFlow()

    private val _dashboard = MutableStateFlow(Async<DashboardResponse>())
    val dashboard: StateFlow<Async<DashboardResponse>> = _dashboard.asStateFlow()

    private val _expenses = MutableStateFlow(Async<List<ExpenseDto>>())
    val expenses: StateFlow<Async<List<ExpenseDto>>> = _expenses.asStateFlow()

    private val _pending = MutableStateFlow(Async<List<ExpenseDto>>())
    val pending: StateFlow<Async<List<ExpenseDto>>> = _pending.asStateFlow()

    private val _detail = MutableStateFlow(Async<ExpenseDetailResponse>())
    val detail: StateFlow<Async<ExpenseDetailResponse>> = _detail.asStateFlow()

    private val _categories = MutableStateFlow(Async<List<CategoryDto>>())
    val categories: StateFlow<Async<List<CategoryDto>>> = _categories.asStateFlow()

    private val _members = MutableStateFlow(Async<List<MemberDto>>())
    val members: StateFlow<Async<List<MemberDto>>> = _members.asStateFlow()

    private val _contributions = MutableStateFlow(Async<ContributionsResponse>())
    val contributions: StateFlow<Async<ContributionsResponse>> = _contributions.asStateFlow()

    private val _report = MutableStateFlow(Async<MonthlyReportResponse>())
    val report: StateFlow<Async<MonthlyReportResponse>> = _report.asStateFlow()

    private val _trend = MutableStateFlow(Async<TrendResponse>())
    val trend: StateFlow<Async<TrendResponse>> = _trend.asStateFlow()

    private val _settlement = MutableStateFlow(Async<SettlementResponse>())
    val settlement: StateFlow<Async<SettlementResponse>> = _settlement.asStateFlow()

    private val _activity = MutableStateFlow(Async<ActivityResponse>())
    val activity: StateFlow<Async<ActivityResponse>> = _activity.asStateFlow()

    private val _months = MutableStateFlow(Async<MonthsResponse>())
    val months: StateFlow<Async<MonthsResponse>> = _months.asStateFlow()

    /** Free-text expense search. Empty means "no search applied". */
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _filters = MutableStateFlow(ExpenseFilters())
    val filters: StateFlow<ExpenseFilters> = _filters.asStateFlow()

    /**
     * Set while a sign-up is waiting on the Admin. The account exists and the
     * token is valid, but the person is in no flat — without this the app
     * would drop them into an empty shell with nothing explaining why.
     */
    private val _pendingJoin = MutableStateFlow<PendingJoinDto?>(null)
    val pendingJoin: StateFlow<PendingJoinDto?> = _pendingJoin.asStateFlow()

    private val _flatSearch = MutableStateFlow<List<FlatSummaryDto>>(emptyList())
    val flatSearch: StateFlow<List<FlatSummaryDto>> = _flatSearch.asStateFlow()

    private val _joinRequests = MutableStateFlow(Async<List<JoinRequestDto>>())
    val joinRequests: StateFlow<Async<List<JoinRequestDto>>> = _joinRequests.asStateFlow()

    private val _toast = MutableStateFlow<ToastMessage?>(null)
    val toast: StateFlow<ToastMessage?> = _toast.asStateFlow()

    private fun currentMonth(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    private fun groupId(): Long = session.value.groupId

    fun notify(text: String) {
        _toast.value = ToastMessage(text)
    }

    fun clearToast() {
        _toast.value = null
    }

    fun clearAuthError() {
        _auth.value = _auth.value.copy(error = null)
    }

    fun setApiBase(value: String) {
        viewModelScope.launch {
            repo.sessionStore.setApiBase(value)
            repo.invalidate()
        }
    }

    fun setMonth(value: String) {
        _month.value = value
        refreshAll()
    }

    /** Steps the selected month backwards or forwards. */
    fun shiftMonth(delta: Int) {
        val parts = _month.value.split("-")
        val year = parts[0].toIntOrNull() ?: return
        val monthNumber = parts.getOrNull(1)?.toIntOrNull() ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthNumber - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        setMonth("%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1))
    }

    // -- auth ---------------------------------------------------------------

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _auth.value = AuthUiState(loading = true)
            repo.invalidate()
            val result = repo.call { it.login(LoginRequest(email.trim(), password)) }
            result.fold(
                onSuccess = { auth ->
                    // The login reply does not say which flat; /me does.
                    repo.sessionStore.save(
                        token = auth.token,
                        userId = auth.user.id,
                        userName = auth.user.name,
                        groupId = 0,
                        groupName = "",
                        isAdmin = false
                    )
                    repo.invalidate()
                    val me = repo.call { it.me() }
                    me.fold(
                        onSuccess = { profile ->
                            val group = profile.groups.firstOrNull()
                            if (group == null) {
                                _auth.value = AuthUiState(
                                    error = "Your account is not in a flat yet. Ask your Admin to add you."
                                )
                                repo.sessionStore.signOut()
                            } else {
                                repo.sessionStore.save(
                                    token = auth.token,
                                    userId = profile.user.id,
                                    userName = profile.user.name,
                                    groupId = group.id,
                                    groupName = group.name,
                                    isAdmin = group.isAdmin
                                )
                                repo.invalidate()
                                _auth.value = AuthUiState()
                                refreshAll()
                            }
                        },
                        onFailure = { _auth.value = AuthUiState(error = it.message) }
                    )
                },
                onFailure = { _auth.value = AuthUiState(error = it.message) }
            )
        }
    }

    fun register(name: String, email: String, password: String, groupName: String) {
        viewModelScope.launch {
            _auth.value = AuthUiState(loading = true)
            repo.invalidate()
            val result = repo.call {
                it.register(
                    RegisterRequest(
                        name = name.trim(),
                        email = email.trim(),
                        password = password,
                        groupName = groupName.trim()
                    )
                )
            }
            result.fold(
                onSuccess = { auth ->
                    val group = auth.group
                    if (group == null) {
                        _auth.value = AuthUiState(error = "Flat was not created. Try again.")
                        return@fold
                    }
                    _pendingJoin.value = null
                    repo.sessionStore.save(
                        token = auth.token,
                        userId = auth.user.id,
                        userName = auth.user.name,
                        groupId = group.id,
                        groupName = group.name,
                        isAdmin = true
                    )
                    repo.invalidate()
                    _auth.value = AuthUiState()
                    refreshAll()
                },
                onFailure = { _auth.value = AuthUiState(error = it.message) }
            )
        }
    }

    private var flatSearchJob: Job? = null

    /** Debounced and cancelling, for the same reason the expense search is. */
    fun searchFlats(term: String) {
        flatSearchJob?.cancel()
        if (term.trim().length < 3) {
            _flatSearch.value = emptyList()
            return
        }
        flatSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            repo.call { it.findFlats(term.trim()) }.fold(
                onSuccess = { _flatSearch.value = it.flats },
                onFailure = { _flatSearch.value = emptyList() }
            )
        }
    }

    /**
     * Signs up asking to join an existing flat. The account is created and the
     * token is kept, but no session is stored — there is no flat to open yet,
     * so the app shows the waiting screen instead.
     */
    fun requestToJoin(
        name: String,
        email: String,
        password: String,
        flat: FlatSummaryDto,
        message: String
    ) {
        viewModelScope.launch {
            _auth.value = AuthUiState(loading = true)
            repo.invalidate()
            repo.call {
                it.register(
                    RegisterRequest(
                        name = name.trim(),
                        email = email.trim(),
                        password = password,
                        joinGroupId = flat.id,
                        message = message.trim().ifBlank { null }
                    )
                )
            }.fold(
                onSuccess = { auth ->
                    _auth.value = AuthUiState()
                    _pendingJoin.value = auth.pendingJoin
                        ?: PendingJoinDto(flat.id, flat.name, "pending")
                },
                onFailure = { _auth.value = AuthUiState(error = it.message) }
            )
        }
    }

    /** Re-checks whether the Admin has decided yet. */
    fun refreshPendingJoin(email: String, password: String) {
        viewModelScope.launch {
            _auth.value = AuthUiState(loading = true)
            repo.invalidate()
            repo.call { it.login(LoginRequest(email.trim(), password)) }.fold(
                onSuccess = { auth ->
                    repo.sessionStore.save(
                        token = auth.token,
                        userId = auth.user.id,
                        userName = auth.user.name,
                        groupId = 0,
                        groupName = "",
                        isAdmin = false
                    )
                    repo.invalidate()
                    repo.call { it.me() }.fold(
                        onSuccess = { me ->
                            val group = me.groups.firstOrNull()
                            if (group != null) {
                                // Approved: the session can finally be stored.
                                repo.sessionStore.save(
                                    token = auth.token,
                                    userId = me.user.id,
                                    userName = me.user.name,
                                    groupId = group.id,
                                    groupName = group.name,
                                    isAdmin = group.isAdmin
                                )
                                repo.invalidate()
                                _pendingJoin.value = null
                                _auth.value = AuthUiState()
                                refreshAll()
                            } else {
                                repo.sessionStore.signOut()
                                _pendingJoin.value = me.pendingJoin
                                _auth.value = AuthUiState()
                            }
                        },
                        onFailure = { _auth.value = AuthUiState(error = it.message) }
                    )
                },
                onFailure = { _auth.value = AuthUiState(error = it.message) }
            )
        }
    }

    fun clearPendingJoin() {
        _pendingJoin.value = null
        _auth.value = AuthUiState()
    }

    // -- join requests, seen by the Admin -----------------------------------

    fun loadJoinRequests() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _joinRequests.value = _joinRequests.value.copy(loading = true, error = null)
            repo.call { it.joinRequests(id) }.fold(
                onSuccess = { _joinRequests.value = Async(data = it.requests) },
                onFailure = { _joinRequests.value = Async(error = it.message) }
            )
        }
    }

    fun decideJoin(requestId: Long, approve: Boolean, who: String) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                if (approve) it.approveJoin(id, requestId) else it.declineJoin(id, requestId)
            }.fold(
                onSuccess = {
                    notify(if (approve) "$who joined the flat" else "$who was declined")
                    loadJoinRequests()
                    loadMembers()
                },
                onFailure = { notify(it.message ?: "Could not decide that request") }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repo.sessionStore.signOut()
            repo.invalidate()
            _dashboard.value = Async()
            _expenses.value = Async()
            _pending.value = Async()
            _contributions.value = Async()
            _report.value = Async()
        }
    }

    // -- loaders ------------------------------------------------------------

    fun refreshAll() {
        loadDashboard()
        loadExpenses()
        loadPending()
        loadCategories()
        loadMembers()
        loadContributions()
        loadReport()
        loadTrend()
        loadSettlement()
        loadMonths()
    }

    fun loadDashboard() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _dashboard.value = _dashboard.value.copy(loading = true, error = null)
            repo.call { it.dashboard(id, _month.value) }.fold(
                onSuccess = { _dashboard.value = Async(data = it) },
                onFailure = { _dashboard.value = Async(error = it.message) }
            )
        }
    }

    /**
     * The status filter is held here rather than passed in each time, so that
     * typing in the search box does not silently drop the chip the user
     * selected.
     */
    private var statusFilter: String? = null

    fun loadExpenses(status: String? = statusFilter) {
        statusFilter = status
        viewModelScope.launch { fetchExpenses(status) }
    }

    /**
     * The fetch itself, so a caller that needs to cancel it — the search box —
     * can own the coroutine rather than firing one that outlives the keystroke
     * that started it.
     */
    private suspend fun fetchExpenses(status: String? = statusFilter) {
        val id = groupId().takeIf { it > 0 } ?: return
        val term = _search.value.trim().ifBlank { null }
        val f = _filters.value
        _expenses.value = _expenses.value.copy(loading = true, error = null)
        repo.call {
            it.expenses(
                id,
                status = status,
                // Search is global on purpose: looking for "that big
                // electricity bill" is exactly the case where you do not know
                // which month it was in, so restricting it to the selected
                // month would hide the answer. An explicit date range does the
                // same, since the user has said which window they want.
                month = if (term == null && !f.hasDateRange) _month.value else null,
                categoryId = f.categoryId,
                paidBy = f.paidBy,
                minAmount = f.minAmount,
                maxAmount = f.maxAmount,
                from = f.from,
                to = f.to,
                query = term
            )
        }.fold(
            onSuccess = { _expenses.value = Async(data = it.expenses) },
            onFailure = { _expenses.value = Async(error = it.message) }
        )
    }

    fun loadPending() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _pending.value = _pending.value.copy(loading = true, error = null)
            // Deliberately not month-filtered: an approval queue should never
            // hide an old request just because the month selector moved on.
            repo.call { it.expenses(id, status = "pending") }.fold(
                onSuccess = { _pending.value = Async(data = it.expenses) },
                onFailure = { _pending.value = Async(error = it.message) }
            )
        }
    }

    fun loadDetail(expenseId: Long) {
        viewModelScope.launch {
            _detail.value = Async(loading = true)
            repo.call { it.expenseDetail(expenseId) }.fold(
                onSuccess = { _detail.value = Async(data = it) },
                onFailure = { _detail.value = Async(error = it.message) }
            )
        }
    }

    fun loadCategories(includeInactive: Boolean = false) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _categories.value = _categories.value.copy(loading = true, error = null)
            repo.call {
                it.categories(id, if (includeInactive) "1" else null)
            }.fold(
                onSuccess = { _categories.value = Async(data = it.categories) },
                onFailure = { _categories.value = Async(error = it.message) }
            )
        }
    }

    fun loadMembers() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _members.value = _members.value.copy(loading = true, error = null)
            repo.call { it.members(id) }.fold(
                onSuccess = { _members.value = Async(data = it.members) },
                onFailure = { _members.value = Async(error = it.message) }
            )
        }
    }

    fun loadContributions() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _contributions.value = _contributions.value.copy(loading = true, error = null)
            repo.call { it.contributions(id, _month.value) }.fold(
                onSuccess = { _contributions.value = Async(data = it) },
                onFailure = { _contributions.value = Async(error = it.message) }
            )
        }
    }

    fun loadReport() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _report.value = _report.value.copy(loading = true, error = null)
            repo.call { it.monthlyReport(id, _month.value) }.fold(
                onSuccess = { _report.value = Async(data = it) },
                onFailure = { _report.value = Async(error = it.message) }
            )
        }
    }

    fun loadTrend(months: Int = 6) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _trend.value = _trend.value.copy(loading = true, error = null)
            repo.call { it.trend(id, months, _month.value) }.fold(
                onSuccess = { _trend.value = Async(data = it) },
                onFailure = { _trend.value = Async(error = it.message) }
            )
        }
    }

    fun loadSettlement() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _settlement.value = _settlement.value.copy(loading = true, error = null)
            repo.call { it.settlement(id, _month.value) }.fold(
                onSuccess = { _settlement.value = Async(data = it) },
                onFailure = { _settlement.value = Async(error = it.message) }
            )
        }
    }

    fun loadActivity() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _activity.value = _activity.value.copy(loading = true, error = null)
            repo.call { it.activity(id) }.fold(
                onSuccess = { _activity.value = Async(data = it) },
                onFailure = { _activity.value = Async(error = it.message) }
            )
        }
    }

    fun loadMonths() {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            _months.value = _months.value.copy(loading = true, error = null)
            repo.call { it.months(id, _month.value) }.fold(
                onSuccess = { _months.value = Async(data = it) },
                onFailure = { _months.value = Async(error = it.message) }
            )
        }
    }

    fun setFilters(value: ExpenseFilters) {
        _filters.value = value
        loadExpenses()
    }

    fun clearFilters() {
        _filters.value = ExpenseFilters()
        loadExpenses()
    }

    private var searchJob: Job? = null

    /**
     * Typing filters the list; an empty box means no search term is sent.
     *
     * Firing a request per keystroke raced badly: typing "rent" issued four
     * searches, and the reply to "r" — which matches nearly every row — could
     * land after the reply to "rent" and overwrite it, leaving the list
     * showing results for a prefix the user had already finished typing.
     * Cancelling the previous job makes the newest query the only one that can
     * write, and the short wait means an ordinary word costs one request
     * rather than four.
     */
    fun setSearch(value: String) {
        _search.value = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            fetchExpenses()
        }
    }

    // -- actions ------------------------------------------------------------

    fun addExpense(
        categoryId: Long,
        description: String,
        amount: String,
        paidBy: Long,
        splitTo: Long,
        expenseDate: String,
        onDone: () -> Unit
    ) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.createExpense(
                    id,
                    CreateExpenseRequest(
                        categoryId = categoryId,
                        description = description.trim(),
                        amount = amount,
                        paidBy = paidBy,
                        splitTo = splitTo,
                        expenseDate = expenseDate
                    )
                )
            }.fold(
                onSuccess = {
                    notify("Expense submitted for approval")
                    refreshAll()
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not add the expense") }
            )
        }
    }

    /**
     * Section 15 forbids silently editing a decided expense. The server sends
     * back `reopened` when it has reset an approved or rejected expense to
     * Pending, and the message says so rather than leaving the user to find out
     * from the status chip.
     */
    fun editExpense(
        expenseId: Long,
        categoryId: Long,
        description: String,
        amount: String,
        paidBy: Long,
        splitTo: Long,
        expenseDate: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            repo.call {
                it.updateExpense(
                    expenseId,
                    UpdateExpenseRequest(
                        categoryId = categoryId,
                        description = description.trim(),
                        amount = amount,
                        paidBy = paidBy,
                        splitTo = splitTo,
                        expenseDate = expenseDate
                    )
                )
            }.fold(
                onSuccess = { result ->
                    notify(
                        if (result.reopened) "Saved — back to Pending for re-approval"
                        else "Changes saved"
                    )
                    loadDetail(expenseId)
                    refreshAll()
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not save the changes") }
            )
        }
    }

    fun cancelExpense(expenseId: Long, reason: String) {
        viewModelScope.launch {
            repo.call { it.cancel(expenseId, DecisionRequest(reason.trim())) }.fold(
                onSuccess = {
                    notify("Expense cancelled")
                    loadDetail(expenseId)
                    refreshAll()
                },
                onFailure = { notify(it.message ?: "Could not cancel") }
            )
        }
    }

    fun approve(expenseId: Long) {
        viewModelScope.launch {
            repo.call { it.approve(expenseId) }.fold(
                onSuccess = {
                    notify("Approved")
                    loadDetail(expenseId)
                    refreshAll()
                },
                onFailure = { notify(it.message ?: "Could not approve") }
            )
        }
    }

    fun reject(expenseId: Long, reason: String) {
        viewModelScope.launch {
            repo.call { it.reject(expenseId, DecisionRequest(reason.trim())) }.fold(
                onSuccess = {
                    notify("Rejected")
                    loadDetail(expenseId)
                    refreshAll()
                },
                onFailure = { notify(it.message ?: "Could not reject") }
            )
        }
    }

    fun recordContribution(userId: Long, paidAmount: String) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.recordContribution(
                    id,
                    RecordContributionRequest(
                        userId = userId,
                        paidAmount = paidAmount,
                        month = _month.value
                    )
                )
            }.fold(
                onSuccess = {
                    notify("Contribution recorded")
                    loadContributions()
                    loadDashboard()
                },
                onFailure = { notify(it.message ?: "Could not record that") }
            )
        }
    }

    fun setExpectedForAll(amount: String) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.setExpectedForAll(id, BulkExpectedRequest(amount, _month.value))
            }.fold(
                onSuccess = {
                    notify("Monthly contribution set for everyone")
                    loadContributions()
                    loadDashboard()
                },
                onFailure = { notify(it.message ?: "Could not set the amount") }
            )
        }
    }

    fun addCategory(name: String, parentId: Long? = null) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.addCategory(id, CreateCategoryRequest(name.trim(), parentId = parentId))
            }.fold(
                onSuccess = {
                    notify("Category added")
                    loadCategories(includeInactive = true)
                },
                onFailure = { notify(it.message ?: "Could not add the category") }
            )
        }
    }

    fun setCategoryActive(categoryId: Long, active: Boolean) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.updateCategory(id, categoryId, UpdateCategoryRequest(isActive = active))
            }.fold(
                onSuccess = {
                    notify(if (active) "Category enabled" else "Category disabled")
                    loadCategories(includeInactive = true)
                },
                onFailure = { notify(it.message ?: "Could not update the category") }
            )
        }
    }

    fun addMember(name: String, email: String, password: String, onDone: () -> Unit) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.addMember(id, CreateMemberRequest(name.trim(), email.trim(), password))
            }.fold(
                onSuccess = {
                    notify("Member added")
                    loadMembers()
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not add the member") }
            )
        }
    }

    /**
     * Deactivating frees one of the five slots. The server refuses to
     * deactivate the Admin, so that case surfaces as its own message rather
     * than being hidden by disabling the control.
     */
    fun setMemberActive(userId: Long, active: Boolean) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call {
                it.updateMember(id, userId, UpdateMemberRequest(if (active) "active" else "inactive"))
            }.fold(
                onSuccess = {
                    notify(if (active) "Member reactivated" else "Member deactivated")
                    loadMembers()
                    refreshAll()
                },
                onFailure = { notify(it.message ?: "Could not update the member") }
            )
        }
    }

    /**
     * Hands the Admin role to someone else. This revokes the caller's own
     * rights, so the stored isAdmin flag is re-read from the server instead of
     * being assumed — otherwise the UI would keep showing Admin controls that
     * every request now rejects.
     */
    fun transferAdmin(userId: Long, onDone: () -> Unit) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call { it.transferAdmin(id, TransferAdminRequest(userId)) }.fold(
                onSuccess = {
                    repo.call { api -> api.me() }.onSuccess { me ->
                        val mine = me.groups.firstOrNull { group -> group.id == id }
                        if (mine != null) repo.sessionStore.setIsAdmin(mine.isAdmin)
                    }
                    notify("Admin transferred")
                    loadMembers()
                    refreshAll()
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not transfer admin") }
            )
        }
    }

    /**
     * Closing reports how many undecided expenses were moved into the next
     * month, because that is a change the Admin did not explicitly ask for and
     * should not have to discover later.
     */
    fun closeMonth(note: String, onDone: () -> Unit) {
        val id = groupId().takeIf { it > 0 } ?: return
        val month = _month.value
        viewModelScope.launch {
            repo.call {
                it.closeMonth(id, month, CloseMonthRequest(note.trim().ifBlank { null }))
            }.fold(
                onSuccess = { result ->
                    notify(
                        if (result.carriedOver > 0) {
                            "${formatMonth(month)} closed — ${result.carriedOver} pending expense(s) moved to the next month"
                        } else {
                            "${formatMonth(month)} closed"
                        }
                    )
                    refreshAll()
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not close the month") }
            )
        }
    }

    fun reopenMonth(reason: String, onDone: () -> Unit) {
        val id = groupId().takeIf { it > 0 } ?: return
        val month = _month.value
        viewModelScope.launch {
            repo.call { it.reopenMonth(id, month, ReopenMonthRequest(reason.trim())) }.fold(
                onSuccess = {
                    notify("${formatMonth(month)} reopened")
                    refreshAll()
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not reopen the month") }
            )
        }
    }

    fun changePassword(currentPassword: String, newPassword: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.call {
                it.changePassword(ChangePasswordRequest(currentPassword, newPassword))
            }.fold(
                onSuccess = {
                    notify("Password changed")
                    onDone()
                },
                onFailure = { notify(it.message ?: "Could not change the password") }
            )
        }
    }
}
