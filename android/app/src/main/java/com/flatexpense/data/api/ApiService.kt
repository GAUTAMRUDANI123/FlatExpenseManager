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

    @POST("api/auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): OkResponse

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

    /** Deactivating frees a slot against the five-member cap; the Admin cannot be deactivated. */
    @PATCH("api/groups/{groupId}/members/{userId}")
    suspend fun updateMember(
        @Path("groupId") groupId: Long,
        @Path("userId") userId: Long,
        @Body body: UpdateMemberRequest
    ): OkResponse

    @POST("api/groups/{groupId}/transfer-admin")
    suspend fun transferAdmin(
        @Path("groupId") groupId: Long,
        @Body body: TransferAdminRequest
    ): OkResponse

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
        /** Free text over description, category and payer name. */
        @Query("q") query: String? = null,
        @Query("limit") limit: Int = 100
    ): ExpenseListResponse

    @POST("api/groups/{groupId}/expenses")
    suspend fun createExpense(
        @Path("groupId") groupId: Long,
        @Body body: CreateExpenseRequest
    ): ExpenseWrapper

    @GET("api/expenses/{expenseId}")
    suspend fun expenseDetail(@Path("expenseId") expenseId: Long): ExpenseDetailResponse

    /**
     * Editing an approved or rejected expense sends it back to Pending — the
     * response's `reopened` flag says whether that happened.
     */
    @PATCH("api/expenses/{expenseId}")
    suspend fun updateExpense(
        @Path("expenseId") expenseId: Long,
        @Body body: UpdateExpenseRequest
    ): UpdateExpenseResponse

    @POST("api/expenses/{expenseId}/approve")
    suspend fun approve(@Path("expenseId") expenseId: Long): ExpenseWrapper

    @POST("api/expenses/{expenseId}/reject")
    suspend fun reject(
        @Path("expenseId") expenseId: Long,
        @Body body: DecisionRequest
    ): ExpenseWrapper

    /** Table 4's optional status: withdraws an expense that is Pending or already Approved. */
    @POST("api/expenses/{expenseId}/cancel")
    suspend fun cancel(
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

    /** Approved spend and contributions received per month, oldest first. */
    @GET("api/groups/{groupId}/reports/trend")
    suspend fun trend(
        @Path("groupId") groupId: Long,
        @Query("months") months: Int = 6,
        @Query("month") month: String? = null
    ): TrendResponse

    @GET("api/groups/{groupId}/settlement")
    suspend fun settlement(
        @Path("groupId") groupId: Long,
        @Query("month") month: String? = null
    ): SettlementResponse

    @GET("api/groups/{groupId}/activity")
    suspend fun activity(
        @Path("groupId") groupId: Long,
        @Query("limit") limit: Int = 100
    ): ActivityResponse

    @GET("api/groups/{groupId}/months")
    suspend fun months(
        @Path("groupId") groupId: Long,
        @Query("month") month: String? = null
    ): MonthsResponse

    @POST("api/groups/{groupId}/months/{month}/close")
    suspend fun closeMonth(
        @Path("groupId") groupId: Long,
        @Path("month") month: String,
        @Body body: CloseMonthRequest
    ): CloseMonthResponse

    @POST("api/groups/{groupId}/months/{month}/reopen")
    suspend fun reopenMonth(
        @Path("groupId") groupId: Long,
        @Path("month") month: String,
        @Body body: ReopenMonthRequest
    ): OkResponse
}
