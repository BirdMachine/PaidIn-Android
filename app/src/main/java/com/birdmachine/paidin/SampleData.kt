package com.birdmachine.paidin

object SampleData {
    val jobs = listOf(
        Job("aperture-2048", "Senior Front-End Engineer", "Aperture BioSystems", "Remote, US", "Remote", 125000, 155000, "Greenhouse", "Job ID", "AB-2048", "Build accessible scientific software in React and TypeScript with Python API collaborators.", listOf("React", "TypeScript", "Accessibility", "Python"), 96),
        Job("cumulus-998877", "Senior React Engineer", "Cumulus Health Data", "Pittsburgh, PA", "Hybrid", 115000, 145000, "Lever", "Requisition", "998877", "Complex healthcare-data workflows, production debugging, responsive UI, and Python collaboration.", listOf("React", "TypeScript", "Production support"), 91),
        Job("oceanic-1142", "Frontend Platform Engineer", "Oceanic Systems", "Remote, US", "Remote", 135000, 170000, "Ashby", "Opening ID", "OS-1142", "Own reusable component infrastructure and frontend reliability for data-heavy applications.", listOf("React", "Design systems", "Testing"), 86),
        Job("gray-441", "Frontend Engineering Manager", "Gray Rectangle Holdings", "New York, NY", "Onsite", 150000, 180000, "Workday", "JobID", "MGR-441", "People manager responsible for eight direct reports, hiring and performance reviews.", listOf("People management", "Hiring"), 24),
    )

    val rules = listOf(
        MarketRule("work", "Accepted work arrangements", RuleKind.QUALIFIER, "remote_status", "in", "remote, hybrid"),
        MarketRule("salary", "Salary ceiling reaches floor", RuleKind.QUALIFIER, "salary_max", ">=", "90000"),
        MarketRule("manager", "No people-management-first roles", RuleKind.DISQUALIFIER, "description", "contains", "direct reports, people manager, manage a team", false),
        MarketRule("remote", "Prefer remote", RuleKind.PREFERENCE, "remote_status", "equals", "remote"),
    )
}
