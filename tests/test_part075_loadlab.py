"""Offline unit tests for addon helpers. Run from seatlock root: python3 -m unittest discover -s tests -p 'test_part075_loadlab.py'"""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import part075_load_100 as race
import part075_lb_smoke as lb


class LoadLabHelperTests(unittest.TestCase):
    def test_nearest_rank_percentiles(self):
        self.assertEqual(race.percentile([1, 2, 3, 4, 5], 50), 3)
        self.assertEqual(race.percentile([1, 2, 3, 4, 5], 95), 5)
        self.assertIsNone(race.percentile([], 95))

    def test_winner_requires_confirmed_receipt(self):
        self.assertTrue(race.result_is_winner({'status': 201, 'body': {'status': 'CONFIRMED'}}))
        self.assertFalse(race.result_is_winner({'status': 201, 'body': {'status': 'UNCERTAIN'}}))
        self.assertFalse(race.result_is_winner({'status': 409, 'body': {'status': 'CONFIRMED'}}))

    def test_only_available_seats_eligible(self):
        seats = {'content': [{'id': 'available', 'status': 'AVAILABLE'},
                             {'id': 'occupied', 'status': 'RESERVED'}]}
        with patch.object(race, 'request', return_value=(200, seats, {})):
            self.assertEqual(race.selected_seat('http://localhost', 'event', None), 'available')
            with self.assertRaises(RuntimeError):
                race.selected_seat('http://localhost', 'event', 'occupied')

    def test_lb_seat_listing_filters_held_reserved(self):
        response = {'content': [{'id': 'one', 'status': 'AVAILABLE'},
                                {'id': 'two', 'status': 'HELD'},
                                {'id': 'three', 'status': 'RESERVED'}]}
        with patch.object(lb, 'request', return_value=(200, response, {})):
            self.assertEqual(lb.seats_from_gateway('http://localhost', 'event'), ['one'])

    def test_idempotency_key_forwarded(self):
        with patch.object(lb, 'request', return_value=(201, {'id': 'id'}, {})) as fake:
            lb.post('http://127.0.0.1:8084', 'token', 'event', 'seat', 'same-key')
            self.assertEqual(fake.call_args.kwargs['headers']['Idempotency-Key'], 'same-key')
            self.assertEqual(fake.call_args.kwargs['payload']['seatId'], 'seat')


if __name__ == '__main__':
    unittest.main()
