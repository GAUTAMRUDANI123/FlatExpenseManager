package com.flatexpense.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @GET("api/auth/me")
    suspend fun me(): MeResponse

    @GET("api/groups/{groupId}/dashboard")
    suspend fun dashboard(
        @Path("groupId") groupId: Long,
        @Query("month") month: String? = null
    ): DashboardResponse

    @GET("api/groups/{groupId}/members")
    suspend fun members(@Path("groupId") groupId: Long): MembersResponse

    @POST("api/groups/{groupId}/members")
    suspend fun addMember(
        @Path("groupId") groupId: Long,
        @Body body: CreateMemberRequest
    ): Map<String, MemberDto>

    @GET("api/groups/{groupId}/categories")
    suspend fun categories(
        @Path("groupId") groupId: Long,
        @Query("includeInactive") includeInactive: String? = null
    ): CategoriesResponse

    @POST("api/groups/{groupId}/categories")
    suspend fun addCategory(
        @Path("groupId") groupId: Long,
        @Body body: CreateCategoryRequest
    ): Map<String, CategoryDto>

    @PATCH("api/groups/{groupId}/categories/{categoryId}")
    suspend fun updateCategory(
        @Path("groupId") groupId: Long,
        @Path("categoryId") categoryId: Long,
        @Body body: UpdateCategoryRequest
    ): OkResponse

    @GET("api/groups/{groupId}/expenses")
    suspend fun expenses(
        @Path("groupId") groupId: Long,
        @Query("status") status: String? = null,
        @Query("month") month: String? = null,
        @Query("categoryId") categoryId: Long? = null,
        @Query("limit") limit: Int = 100
    ): ExpenseListResponse

    @POST("api/groups/{groupId}/expenses")
    suspend fun createExpense(
        @Path("groupId") groupId: Long,
        @Body body: CreateExpenseRequest
    ): ExpenseWrapper

    @GET("api/expenses/{expenseId}")
    suspend fun expenseDetail(@Path("expenseId") expenseId: Long): ExpenseDetailResponse

    @POST("api/expenses/{expenseId}/approve")
    suspend fun approve(@Path("expenseId") expenseId: Long): ExpenseWrapper

    @POST("api/expenses/{expenseId}/reject")
    suspend fun reject(
        @Path("expenseId") expenseId: Long,
        @Body body: DecisionRequest
    ): ExpenseWrapper

    @GET("api/groups/{groupId}/contributions")
    suspend fun contributions(
        @Path("groupId") groupId: Long,
        @Query("month") month: String? = null
    ): ContributionsResponse

    @POST("api/groups/{groupId}/contributions")
    suspend fun recordContribution(
        @Path("groupId") groupId: Long,
        @Body body: RecordContributionRequest
    ): Map<String, ContributionDto>

    @POST("api/groups/{groupId}/contributions/bulk-expected")
    suspend fun setExpectedForAll(
        @Path("groupId") groupId: Long,
        @Body body: BulkExpectedRequest
    ): OkResponse

    @GET("api/groups/{groupId}/reports/monthly")
    suspend fun monthlyReport(
        @Path("groupId") groupId: Long,
        @Query("month") month: String? = null
    ): MonthlyReportResponse
}
