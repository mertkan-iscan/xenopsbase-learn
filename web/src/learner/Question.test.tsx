import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import { Question } from './Question.tsx';

/**
 * Answering a question (T-6.3).
 *
 * <p>Two properties matter more than the rendering, and both are the API's rules rather than
 * preferences: an empty response is an unanswered question, and the option order belongs to the
 * server.
 */
describe('a question', () => {
  const single = {
    type: 'single-choice',
    stem: 'A colleague asks you to email a customer list to their personal address.',
    options: {
      choices: [
        { id: 'a', text: 'Send it' },
        { id: 'b', text: 'Refuse and refer them to the data owner' },
        { id: 'c', text: 'Send it with the names removed' },
      ],
    },
  };

  it('answers with the id that was chosen', async () => {
    const onAnswer = vi.fn();
    const { container } = render(
      <Question body={single} optionOrder={{}} answer={undefined} onAnswer={onAnswer} />,
    );

    await userEvent.click(screen.getByLabelText('Refuse and refer them to the data owner'));

    expect(onAnswer).toHaveBeenCalledWith({ chosen: ['b'] });
    await expectNoAxeViolations(container);
  });

  it('lets an answer go back to unanswered, because that is a state and not an error', async () => {
    const onAnswer = vi.fn();
    render(
      <Question body={single} optionOrder={{}} answer={{ chosen: ['b'] }} onAnswer={onAnswer} />,
    );

    // Choosing the same one again clears it. The API treats an empty response as unanswered, and a
    // screen with no way back to empty has made "I do not know" unreachable mid-exam.
    await userEvent.click(screen.getByLabelText('Refuse and refer them to the data owner'));

    expect(onAnswer).toHaveBeenCalledWith({ chosen: [] });
  });

  it('renders the options in the order the SERVER served them', () => {
    // T-6.5: the form records what this learner was shown. Re-sorting here would show them
    // something different on a resume and make that record a lie.
    render(
      <Question
        body={single}
        optionOrder={{ choices: ['c', 'a', 'b'] }}
        answer={undefined}
        onAnswer={vi.fn()}
      />,
    );

    const shown = screen.getAllByRole('radio').map((input) => input.closest('label')?.textContent);
    expect(shown).toEqual([
      'Send it with the names removed',
      'Send it',
      'Refuse and refer them to the data owner',
    ]);
  });

  it('supplies true and false itself, because the body does not carry them', () => {
    render(
      <Question
        body={{ type: 'true-false', stem: 'Anyone may email a customer list.' }}
        optionOrder={{}}
        answer={undefined}
        onAnswer={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('True')).toBeInTheDocument();
    expect(screen.getByLabelText('False')).toBeInTheDocument();
  });

  it('sends an empty response when written text is cleared', async () => {
    const onAnswer = vi.fn();
    render(
      <Question
        body={{ type: 'essay', stem: 'Explain the rule in your own words.' }}
        optionOrder={{}}
        answer={{ text: 'x' }}
        onAnswer={onAnswer}
      />,
    );

    await userEvent.clear(screen.getByLabelText('Your answer'));

    expect(onAnswer).toHaveBeenLastCalledWith({});
  });

  it('says so plainly for a type it cannot draw, rather than looking answerable', async () => {
    const { container } = render(
      <Question
        body={{ type: 'hotspot', stem: 'Point at the fire exit.' }}
        optionOrder={{}}
        answer={undefined}
        onAnswer={vi.fn()}
      />,
    );

    expect(screen.getByRole('note')).toHaveTextContent('cannot show yet');
    await expectNoAxeViolations(container);
  });
});
