package com.flatexpense.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Money arrives as a fixed-2dp string ("1000.00") and stays a string all the
 * way to the formatter. Parsing it into a Double here would reintroduce exactly
 * the drift the DECIMAL column exists to avoid.
 */

@Serializable
data class UserDto(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String? = null,
    val role: String = "member",
    val status: String = "active"
)

@Serializable
data class GroupDto(
    val id: Long,
    val name: String,
    val adminId: Long,
    val isAdmin: Boolean = false
)

@Serializable
data class PersonRef(
    val id: Long,
    val name: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val phone: String? = null,
    val groupName: String? = null
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserDto,
    val group: GroupDto? = null
)

@Serializable
data class MeResponse(
    val user: UserDto,
    val groups: List<GroupDto>
)

@Serializable
data class CategoryDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class CategoriesResponse(val categories: List<CategoryDto>)

@Serializable
data class MemberDto(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String? = null,
    val status: String = "active",
    val isAdmin: Boolean = false
)

@Serializable
data class MembersResponse(
    val adminId: Long,
    val members: List<MemberDto>
)

@Serializable
data class ExpenseDto(
    val id: Long,
    val groupId: Long,
    val categoryId: Long,
    val categoryName: String,
    val categoryIcon: String? = null,
    val description: String,
    val amount: String,
    val expenseDate: String,
    val status: String,
    val paidBy: PersonRef,
    val splitTo: PersonRef,
    val createdBy: PersonRef,
    val approvedBy: PersonRef? = null,
    val rejectionReason: String? = null
)

@Serializable
data class ExpenseListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val expenses: List<ExpenseDto>
)

@Serializable
data class AuditEntryDto(
    val id: Long,
    val action: String,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val detail: String? = null,
    val actor: PersonRef,
    val createdAt: String? = null
)

@Serializable
data class ExpenseDetailResponse(
    val expense: ExpenseDto,
    val canApprove: Boolean,
    val audit: List<AuditEntryDto> = emptyList()
)

@Serializable
data class ExpenseWrapper(val expense: ExpenseDto)

@Serializable
data class CreateExpenseRequest(
    val categoryId: Long,
    val description: String,
    val amount: String,
    val paidBy: Long,
    val splitTo: Long,
    val expenseDate: String
)

@Serializable
data class DecisionRequest(val reason: String? = null)

@Serializable
data class ContributionDto(
    val userId: Long,
    val name: String,
    val isAdmin: Boolean = false,
    val expectedAmount: String,
    val paidAmount: String,
    val status: String,
    val paidAt: String? = null,
    val note: String? = null
)

@Serializable
data class ContributionTotals(
    val expected: String,
    val received: String,
    val pending: String
)

@Serializable
data class ContributionsResponse(
    val month: String,
    val totals: ContributionTotals,
    val contributions: List<ContributionDto>
)

@Serializable
data class RecordContributionRequest(
    val userId: Long,
    val paidAmount: String? = null,
    val expectedAmount: String? = null,
    val month: String? = null,
    val note: String? = null
)

@Serializable
data class BulkExpectedRequest(
    val expectedAmount: String,
    val month: String? = null
)

@Serializable
data class CategoryTotalDto(
    val categoryId: Long,
    val name: String,
    val icon: String? = null,
    val total: String,
    val count: Int
)

@Serializable
data class ExpenseSummaryDto(
    val approvedTotal: String,
    val approvedCount: Int,
    val pendingTotal: String,
    val pendingCount: Int
)

@Serializable
data class MemberStatusDto(
    val userId: Long,
    val name: String,
    val isAdmin: Boolean = false,
    val expectedAmount: String,
    val paidAmount: String,
    val status: String,
    val paidAt: String? = null
)

@Serializable
data class DashboardResponse(
    val month: String,
    val group: GroupDto,
    val isAdmin: Boolean,
    val contributions: ContributionTotals,
    val expenses: ExpenseSummaryDto,
    val balance: String,
    val byCategory: List<CategoryTotalDto>,
    val recentExpenses: List<ExpenseDto>,
    val memberStatus: List<MemberStatusDto>
)

@Serializable
data class StatusTotals(
    val pending: String,
    val approved: String,
    val rejected: String,
    val cancelled: String
)

@Serializable
data class StatusCounts(
    val pending: Int,
    val approved: Int,
    val rejected: Int,
    val cancelled: Int
)

@Serializable
data class ExpenseTotalsDto(
    val byStatus: StatusTotals,
    val counts: StatusCounts
)

@Serializable
data class PersonTotalDto(
    val id: Long,
    val name: String,
    val total: String,
    val count: Int
)

@Serializable
data class MonthlyReportResponse(
    val month: String,
    val contributions: ContributionTotals,
    val expenseTotals: ExpenseTotalsDto,
    val byCategory: List<CategoryTotalDto>,
    val byPaidBy: List<PersonTotalDto>,
    val bySplitTo: List<PersonTotalDto>,
    val balance: String
)

@Serializable
data class CreateMemberRequest(
    val name: String,
    val email: String,
    val password: String,
    val phone: String? = null
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
    val icon: String? = null
)

@Serializable
data class UpdateCategoryRequest(
    val name: String? = null,
    val isActive: Boolean? = null
)

@Serializable
data class ApiErrorBody(
    @SerialName("error") val error: ApiErrorDetail? = null
)

@Serializable
data class ApiErrorDetail(
    val message: String? = null
)

/** Generic acknowledgement; extra fields are ignored by the JSON config. */
@Serializable
data class OkResponse(val ok: Boolean = true)

/**
 * Every field is optional: the PATCH endpoint only touches what it is sent, and
 * `explicitNulls = false` drops the untouched ones from the JSON body rather
 * than sending nulls the server would read as "clear this".
 */
@Serializable
data class UpdateExpenseRequest(
    val categoryId: Long? = null,
    val description: String? = null,
    val amount: String? = null,
    val paidBy: Long? = null,
    val splitTo: Long? = null,
    val expenseDate: String? = null
)

/** `reopened` is true when the edit sent an approved/rejected expense back to Pending. */
@Serializable
data class UpdateExpenseResponse(
    val expense: ExpenseDto,
    val reopened: Boolean = false
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class TransferAdminRequest(val userId: Long)

@Serializable
data class UpdateMemberRequest(val status: String)
