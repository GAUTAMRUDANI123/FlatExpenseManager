package com.flatexpense.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flatexpense.ui.screens.AddExpenseScreen
import com.flatexpense.ui.screens.AppViewModel
import com.flatexpense.ui.screens.CategoriesScreen
import com.flatexpense.ui.screens.ContributionsScreen
import com.flatexpense.ui.screens.DashboardScreen
import com.flatexpense.ui.screens.ExpenseDetailScreen
import com.flatexpense.ui.screens.ExpenseListScreen
import com.flatexpense.ui.screens.LoginScreen
import com.flatexpense.ui.screens.MembersScreen
import com.flatexpense.ui.screens.PendingApprovalsScreen
import com.flatexpense.ui.screens.ProfileScreen
import com.flatexpense.ui.screens.ReportsScreen

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val TABS = listOf(
    Tab("dashboard", "Home", Icons.Filled.Dashboard),
    Tab("expenses", "Expenses", Icons.AutoMirrored.Filled.ListAlt),
    Tab("approvals", "Approve", Icons.Filled.TaskAlt),
    Tab("contributions", "Dues", Icons.Filled.Payments),
    Tab("more", "More", Icons.Filled.Person)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()

    if (!session.isSignedIn) {
        LoginScreen(viewModel)
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    val pendingState by viewModel.pending.collectAsStateWithLifecycle()
    val pendingCount = pendingState.data?.size ?: 0

    val toast by viewModel.toast.collectAsStateWithLifecycle()
    LaunchedEffect(toast?.id) {
        toast?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.clearToast()
        }
    }

    LaunchedEffect(session.groupId) {
        if (session.groupId > 0) viewModel.refreshAll()
    }

    val isTopLevel = TABS.any { it.route == currentRoute }
    val title = when {
        currentRoute == "dashboard" -> session.groupName.ifBlank { "Dashboard" }
        currentRoute == "expenses" -> "Expenses"
        currentRoute == "approvals" -> "Pending approvals"
        currentRoute == "contributions" -> "Monthly contributions"
        currentRoute == "more" -> "More"
        currentRoute == "add" -> "Add expense"
        currentRoute?.startsWith("edit/") == true -> "Edit expense"
        currentRoute == "reports" -> "Monthly report"
        currentRoute == "categories" -> "Categories"
        currentRoute == "members" -> "Members"
        currentRoute == "profile" -> "Profile"
        currentRoute?.startsWith("expense/") == true -> "Expense details"
        else -> "Flat Expenses"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!isTopLevel) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (currentRoute == "dashboard" || currentRoute == "expenses") {
                FloatingActionButton(onClick = { navController.navigate("add") }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add expense")
                }
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == "approvals" && pendingCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            NavHost(navController = navController, startDestination = "dashboard") {
                composable("dashboard") {
                    DashboardScreen(viewModel) { navController.navigate("expense/$it") }
                }
                composable("expenses") {
                    ExpenseListScreen(viewModel) { navController.navigate("expense/$it") }
                }
                composable("approvals") {
                    PendingApprovalsScreen(viewModel) { navController.navigate("expense/$it") }
                }
                composable("contributions") { ContributionsScreen(viewModel) }
                composable("more") { MoreScreen(navController::navigate) }
                composable("add") {
                    AddExpenseScreen(viewModel) { navController.popBackStack() }
                }
                composable("edit/{expenseId}") { entry ->
                    val id = entry.arguments?.getString("expenseId")?.toLongOrNull()
                    if (id != null) {
                        AddExpenseScreen(viewModel, expenseId = id) {
                            navController.popBackStack()
                        }
                    }
                }
                composable("reports") { ReportsScreen(viewModel) }
                composable("categories") { CategoriesScreen(viewModel) }
                composable("members") { MembersScreen(viewModel) }
                composable("profile") { ProfileScreen(viewModel) }
                composable("expense/{expenseId}") { entry ->
                    val id = entry.arguments?.getString("expenseId")?.toLongOrNull()
                    if (id != null) {
                        ExpenseDetailScreen(viewModel, id) { editId ->
                            navController.navigate("edit/$editId")
                        }
                    }
                }
            }
        }
    }
}

private data class MoreEntry(val route: String, val label: String, val icon: ImageVector)

private val MORE_ENTRIES = listOf(
    MoreEntry("reports", "Monthly report", Icons.Filled.BarChart),
    MoreEntry("categories", "Categories", Icons.Filled.Sell),
    MoreEntry("members", "Members", Icons.Filled.Groups),
    MoreEntry("profile", "Profile & settings", Icons.Filled.Person)
)

/** The screens that do not earn a bottom-bar slot of their own. */
@Composable
private fun MoreScreen(navigate: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(MORE_ENTRIES, key = { it.route }) { entry ->
            ListItem(
                headlineContent = { Text(entry.label) },
                leadingContent = { Icon(entry.icon, contentDescription = null) },
                modifier = Modifier.clickable { navigate(entry.route) }
            )
            HorizontalDivider()
        }
    }
}
