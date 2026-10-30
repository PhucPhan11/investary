import { DecimalPipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { errorMessage } from '../../core/api/error-message';
import { InvestorApiService } from '../../core/api/portfolio-api.service';
import {
  ExperienceLevel,
  Goal,
  GoalPriority,
  GoalRequest,
  InstrumentType,
  InvestmentHorizon,
  ProfileRequest,
  RiskTolerance,
} from '../../core/models/portfolio.model';
import { Alert } from '../../shared/components/alert';

interface GoalDraft {
  name: string;
  description: string;
  targetAmount: number | null;
  targetDate: string;
  currency: string;
  priority: GoalPriority;
  notes: string;
}

/**
 * The investor's self-declared profile and goals. Investary records what the user enters and never
 * infers profile values.
 */
@Component({
  selector: 'app-investor-profile',
  imports: [DecimalPipe, FormsModule, Alert],
  templateUrl: './investor-profile.html',
})
export class InvestorProfilePage {
  private readonly api = inject(InvestorApiService);

  protected readonly experienceLevels: ExperienceLevel[] = ['NONE', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED'];
  protected readonly riskLevels: RiskTolerance[] = ['LOW', 'MODERATE', 'HIGH'];
  protected readonly horizons: { value: InvestmentHorizon; label: string }[] = [
    { value: 'SHORT', label: 'Short (< 1 year)' },
    { value: 'MEDIUM', label: 'Medium (1–5 years)' },
    { value: 'LONG', label: 'Long (> 5 years)' },
  ];
  protected readonly instrumentTypes: InstrumentType[] = ['STOCK', 'ETF', 'FUND', 'BOND', 'OTHER'];
  protected readonly priorities: GoalPriority[] = ['HIGH', 'MEDIUM', 'LOW'];

  protected readonly loading = signal(true);
  protected readonly configured = signal(false);
  protected readonly goals = signal<Goal[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly editingGoalId = signal<string | null>(null);

  protected profile: ProfileRequest = {
    experienceLevel: null,
    riskTolerance: null,
    objectives: null,
    investmentHorizon: null,
    preferredInstruments: [],
    restrictions: null,
    liquidityNeeds: null,
    notes: null,
  };
  protected goal: GoalDraft = this.emptyGoal();

  constructor() {
    this.api.profile().subscribe({
      next: (p) => {
        this.configured.set(p.configured);
        this.profile = {
          experienceLevel: p.experienceLevel,
          riskTolerance: p.riskTolerance,
          objectives: p.objectives,
          investmentHorizon: p.investmentHorizon,
          preferredInstruments: [...p.preferredInstruments],
          restrictions: p.restrictions,
          liquidityNeeds: p.liquidityNeeds,
          notes: p.notes,
        };
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the investor profile.'));
      },
    });
    this.loadGoals();
  }

  protected togglePreferred(type: InstrumentType, checked: boolean): void {
    const current = new Set(this.profile.preferredInstruments);
    if (checked) current.add(type);
    else current.delete(type);
    this.profile.preferredInstruments = this.instrumentTypes.filter((t) => current.has(t));
  }

  protected saveProfile(): void {
    this.saving.set(true);
    this.error.set(null);
    this.api.saveProfile(this.profile).subscribe({
      next: (p) => {
        this.saving.set(false);
        this.configured.set(p.configured);
        this.notice.set('Profile saved.');
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'Could not save the profile.'));
      },
    });
  }

  protected goalProblems(): string[] {
    const problems: string[] = [];
    if (!this.goal.name.trim()) problems.push('Name the goal.');
    if (this.goal.targetAmount !== null && !(this.goal.targetAmount > 0)) problems.push('The target amount must be greater than 0.');
    if (!/^[A-Za-z]{3}$/.test(this.goal.currency.trim())) problems.push('Use a 3-letter currency code.');
    return problems;
  }

  protected saveGoal(): void {
    if (this.goalProblems().length) {
      return;
    }
    const g = this.goal;
    const request: GoalRequest = {
      name: g.name.trim(),
      description: g.description.trim() || null,
      targetAmount: g.targetAmount,
      targetDate: g.targetDate || null,
      currency: g.currency.trim().toUpperCase(),
      priority: g.priority,
      notes: g.notes.trim() || null,
    };
    const id = this.editingGoalId();
    (id ? this.api.updateGoal(id, request) : this.api.createGoal(request)).subscribe({
      next: () => {
        this.goal = this.emptyGoal();
        this.editingGoalId.set(null);
        this.loadGoals();
      },
      error: (err) => this.error.set(errorMessage(err, 'Could not save the goal.')),
    });
  }

  protected editGoal(goal: Goal): void {
    this.editingGoalId.set(goal.id);
    this.goal = {
      name: goal.name,
      description: goal.description ?? '',
      targetAmount: goal.targetAmount,
      targetDate: goal.targetDate ?? '',
      currency: goal.currency,
      priority: goal.priority,
      notes: goal.notes ?? '',
    };
  }

  protected cancelGoal(): void {
    this.editingGoalId.set(null);
    this.goal = this.emptyGoal();
  }

  protected deleteGoal(goal: Goal): void {
    if (!confirm(`Delete the goal "${goal.name}"?`)) {
      return;
    }
    this.api.deleteGoal(goal.id).subscribe({
      next: () => this.loadGoals(),
      error: (err) => this.error.set(errorMessage(err, 'Could not delete the goal.')),
    });
  }

  private loadGoals(): void {
    this.api.goals().subscribe({
      next: (goals) => this.goals.set(goals),
      error: (err) => this.error.set(errorMessage(err, 'Could not load goals.')),
    });
  }

  private emptyGoal(): GoalDraft {
    return { name: '', description: '', targetAmount: null, targetDate: '', currency: 'VND', priority: 'MEDIUM', notes: '' };
  }
}
