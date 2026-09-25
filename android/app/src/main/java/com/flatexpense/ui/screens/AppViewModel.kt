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
import com.flatexpense.data.api.LoginRequest
import com.flatexpense.data.api.MemberDto
import com.flatexpense.data.api.MonthlyReportResponse
import com.flatexpense.data.api.RecordContributionRequest
import com.flatexpense.data.api.RegisterRequest
import com.flatexpense.data.api.TransferAdminRequest
import com.flatexpense.data.api.UpdateCategoryRequest
import com.flatexpense.data.api.UpdateExpenseRequest
import com.flatexpense.data.api.UpdateMemberRequest
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
        val id = groupId().takeIf { it > 0 } ?: return
        statusFilter = status
        val term = _search.value.trim().ifBlank { null }
        viewModelScope.launch {
            _expenses.value = _expenses.value.copy(loading = true, error = null)
            repo.call {
                it.expenses(
                    id,
                    status = status,
                    // Search is global on purpose: looking for "that big
                    // electricity bill" is exactly the case where you do not
                    // know which month it was in, so restricting it to the
                    // selected month would hide the answer.
                    month = if (term == null) _month.value else null,
                    query = term
                )
            }.fold(
                onSuccess = { _expenses.value = Async(data = it.expenses) },
                onFailure = { _expenses.value = Async(error = it.message) }
            )
        }
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

    /** Typing filters the list; an empty box means no search term is sent. */
    fun setSearch(value: String) {
        _search.value = value
        loadExpenses()
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

    fun addCategory(name: String) {
        val id = groupId().takeIf { it > 0 } ?: return
        viewModelScope.launch {
            repo.call { it.addCategory(id, CreateCategoryRequest(name.trim())) }.fold(
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
